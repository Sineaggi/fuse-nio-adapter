package org.cryptomator.frontend.fuse.mount;

import com.google.common.base.Preconditions;
import org.cryptomator.frontend.fuse.AdapterFactory;
import org.cryptomator.frontend.fuse.FileNameTranscoder;
import org.cryptomator.integrations.common.OperatingSystem;
import org.cryptomator.integrations.common.Priority;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountBuilder;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountFeature;
import org.cryptomator.integrations.mount.MountProvider;
import org.cryptomator.jfuse.api.Fuse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Set;

import static org.cryptomator.integrations.mount.MountFeature.MOUNT_FLAGS;
import static org.cryptomator.integrations.mount.MountFeature.MOUNT_POINT_EMPTY_DIR;
import static org.cryptomator.integrations.mount.MountFeature.PORT;
import static org.cryptomator.integrations.mount.MountFeature.READ_ONLY;
import static org.cryptomator.integrations.mount.MountFeature.UNMOUNT_FORCED;

/**
 * Mounts a file system on macOS using fuse-t.
 *
 * @see <a href="https://www.fuse-t.org/">fuse-t website</a>
 */
@Priority(90)
@OperatingSystem(OperatingSystem.Value.MAC)
public class FuseTMountProvider implements MountProvider {

	private static final String DYLIB_PATH = "/usr/local/lib/libfuse-t.dylib";
	private static final Path USER_HOME = Paths.get(System.getProperty("user.home"));

	@Override
	public String displayName() {
		return "FUSE-T";
	}

	@Override
	public boolean isSupported() {
		return Files.exists(Paths.get(DYLIB_PATH));
	}

	@Override
	public MountBuilder forFileSystem(Path fileSystemRoot) {
		return new FuseTMountBuilder(fileSystemRoot);
	}

	@Override
	public Set<MountFeature> supportedFeatures() {
		return EnumSet.of(MOUNT_FLAGS, PORT, UNMOUNT_FORCED, READ_ONLY, MOUNT_POINT_EMPTY_DIR);
	}

	@Override
	public int getDefaultPort() {
		return 2049;
	}

	@Override
	public String getDefaultMountFlags(String volumeName) {
		// https://github.com/macos-fuse-t/fuse-t/wiki#supported-mount-options
		return "-ovolname=" + volumeName //
				+ " -orwsize=262144";
	}

	private static class FuseTMountBuilder extends AbstractMacMountBuilder {

		private int port;

		public FuseTMountBuilder(Path vfsRoot) {
			super(vfsRoot);
		}

		@Override
		public MountBuilder setPort(int port) {
			this.port = port;
			return this;
		}

		@Override
		protected Set<String> combinedMountFlags() {
			Set<String> combined = super.combinedMountFlags();
			if (port != 0) {
				combined.add("-l" + port);
			}
			return combined;
		}

		@Override
		public Mount mount() throws MountFailedException {
			Preconditions.checkNotNull(mountPoint);
			Preconditions.checkNotNull(mountFlags);

			var builder = Fuse.builder();
			builder.setLibraryPath(DYLIB_PATH);
			var fuseAdapter = AdapterFactory.createReadWriteAdapter(vfsRoot, //
					builder.errno(), //
					AdapterFactory.DEFAULT_MAX_FILENAMELENGTH, //
					FileNameTranscoder.transcoder().withFuseNormalization(Normalizer.Form.NFD));
			var fuse = builder.build(fuseAdapter);
			try {
				fuse.mount("fuse-nio-adapter", mountPoint, combinedMountFlags().toArray(String[]::new));
				return new MacMountedVolume(fuse, mountPoint);
			} catch (org.cryptomator.jfuse.api.MountFailedException e) {
				throw new MountFailedException(e);
			}
		}
	}

}
