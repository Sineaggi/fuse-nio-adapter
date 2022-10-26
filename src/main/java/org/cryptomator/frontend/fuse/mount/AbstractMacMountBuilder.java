package org.cryptomator.frontend.fuse.mount;

import org.cryptomator.integrations.mount.MountBuilder;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

abstract class AbstractMacMountBuilder implements MountBuilder {

	protected final Path vfsRoot;
	protected Path mountPoint;
	protected Set<String> mountFlags;
	protected boolean readOnly;

	public AbstractMacMountBuilder(Path vfsRoot) {
		this.vfsRoot = vfsRoot;
	}

	@Override
	public MountBuilder setMountpoint(Path mountPoint) {
		this.mountPoint = mountPoint;
		return this;
	}

	@Override
	public MountBuilder setMountFlags(String mountFlagsString) {
		// we assume that each flag starts with "-"
		var notEmpty = Predicate.not(String::isBlank);
		this.mountFlags = Pattern.compile("\\s?-").splitAsStream(mountFlagsString).filter(notEmpty).map("-"::concat).collect(Collectors.toUnmodifiableSet());
		return this;
	}

	@Override
	public MountBuilder setReadOnly(boolean mountReadOnly) {
		this.readOnly = mountReadOnly;
		return this;
	}

	/**
	 * Combines the {@link #setMountFlags(String) mount flags} with any additional option that might have
	 * been set separately.
	 *
	 * @return Mutable set of all currently set mount options
	 */
	protected Set<String> combinedMountFlags() {
		var combined = new HashSet<>(mountFlags);
		if (readOnly) {
			combined.add("-r");
		}
		return combined;
	}

}
