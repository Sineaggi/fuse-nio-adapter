package org.cryptomator.frontend.fuse.mount;

import com.google.common.base.Preconditions;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptofs.DirStructure;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountFeature;
import org.cryptomator.integrations.mount.MountProvider;
import org.cryptomator.integrations.mount.UnmountFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Scanner;

/**
 * Test programs to mirror an existing directory or vault.
 * <p>
 * Run with {@code --enable-native-access=...}
 */
public class MirroringFuseMountTest {

	static {
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
		System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
		System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
	}

	private static final Logger LOG = LoggerFactory.getLogger(MirroringFuseMountTest.class);

	/**
	 * Mirror directory
	 */
	public static class Mirror {

		public static void main(String[] args) throws MountFailedException {
			try (Scanner scanner = new Scanner(System.in)) {
				System.out.println("Enter path to the directory you want to mirror:");
				Path p = Paths.get(scanner.nextLine());
				mount(p, scanner);
			}
		}

	}

	/**
	 * Mirror vault
	 */
	public static class CryptoFsMirror {

		public static void main(String[] args) throws IOException, NoSuchAlgorithmException, MountFailedException {
			try (Scanner scanner = new Scanner(System.in)) {
				System.out.println("Enter path to the vault you want to mirror:");
				Path vaultPath = Paths.get(scanner.nextLine());
				Preconditions.checkArgument(CryptoFileSystemProvider.checkDirStructureForVault(vaultPath, "vault.cryptomator", "masterkey.cryptomator") == DirStructure.VAULT, "Not a vault: " + vaultPath);

				System.out.println("Enter vault password:");
				String passphrase = scanner.nextLine();

				SecureRandom csprng = SecureRandom.getInstanceStrong();
				CryptoFileSystemProperties props = CryptoFileSystemProperties.cryptoFileSystemProperties()
						.withKeyLoader(url -> new MasterkeyFileAccess(new byte[0], csprng).load(vaultPath.resolve("masterkey.cryptomator"), passphrase))
						.build();
				try (FileSystem cryptoFs = CryptoFileSystemProvider.newFileSystem(vaultPath, props)) {
					Path p = cryptoFs.getPath("/");
					mount(p, scanner);
				}
			}
		}

	}

	private static void mount(Path pathToMirror, Scanner scanner) throws MountFailedException {
		var mountProvider = MountProvider.get().findAny().orElseThrow(() -> new MountFailedException("Did not find a mount provider"));
		LOG.info("Using mount provider: {}", mountProvider.displayName());
		var mountBuilder = mountProvider.forFileSystem(pathToMirror);
		if (mountProvider.supportsFeature(MountFeature.MOUNT_FLAGS)) {
			mountBuilder.setMountFlags(mountProvider.getDefaultMountFlags("mirror"));
		}
		if (mountProvider.supportsFeature(MountFeature.MOUNT_TO_SYSTEM_CHOSEN_PATH)) {
			// don't set a mount point
		} else {
			System.out.println("Enter mount point: ");
			Path m = Paths.get(scanner.nextLine());
			mountBuilder.setMountpoint(m);
		}

		try (var mount = mountBuilder.mount()) {
			LOG.info("Mounted successfully to: {}", mount.getMountpoint());
			LOG.info("Enter anything to unmount...");
			System.in.read();

			try {
				mount.unmount();
			} catch (UnmountFailedException e) {
				if (mountProvider.supportsFeature(MountFeature.UNMOUNT_FORCED)) {
					LOG.warn("Graceful unmount failed. Attempting force-unmount...");
					mount.unmountForced();
				}
			}
		} catch (UnmountFailedException e) {
			LOG.warn("Unmount failed.", e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

