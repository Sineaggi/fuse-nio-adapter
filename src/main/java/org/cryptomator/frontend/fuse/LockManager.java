package org.cryptomator.frontend.fuse;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Provides a path-based locking mechanism as described by
 * <a href="https://people.eecs.berkeley.edu/~kubitron/courses/cs262a-F14/projects/reports/project6_report.pdf">Ritik Malhotra here</a>.
 *
 * <p>
 * Usage Example 1:
 * <pre>
 *     try (PathLock pathLock = lockManager.lockPathForReading("/foo/bar/baz"); // path is not manipulated, thus read-locking
 *          DataLock dataLock = pathLock.lockDataForWriting()) { // content is manipulated, thus write-locking
 *          // write to file
 *     }
 * </pre>
 *
 * <p>
 * Usage Example 2:
 * <pre>
 *     try (PathLock srcPathLock = lockManager.lockPathForReading("/foo/bar/original");
 *          DataLock srcDataLock = srcPathLock.lockDataForReading(); // content will only be read, thus read-locking
 *          PathLock dstPathLock = lockManager.lockPathForWriting("/foo/bar/copy"); // file will be created, thus write-locking
 *          DataLock dstDataLock = srcPathLock.lockDataForWriting()) {
 *          // copy from /foo/bar/original to /foo/bar/copy
 *     }
 * </pre>
 */
@PerAdapter
public class LockManager {

	private static final Logger LOG = LoggerFactory.getLogger(LockManager.class);
	private static final char PATH_SEP = '/';
	private static final Splitter PATH_SPLITTER = Splitter.on(PATH_SEP).omitEmptyStrings();
	private static final Joiner PATH_JOINER = Joiner.on(PATH_SEP);

	private final ConcurrentMap<String, ReentrantReadWriteLock> pathLocks = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, ReentrantReadWriteLock> dataLocks = new ConcurrentHashMap<>();

	@Inject
	public LockManager() {
	}

	/*
	 * Read locks:
	 */

	public PathLock lockPathForReading(String absolutePath) {
		List<String> pathComponents = PATH_SPLITTER.splitToList(absolutePath);
		Preconditions.checkArgument(!pathComponents.isEmpty(), "path must not be empty");
		return lockPathForReading(pathComponents);
	}

	private PathLock lockPathForReading(List<String> pathComponents) {
		List<String> parentPathComponents = parentPathComponents(pathComponents);
		lockAncestors(parentPathComponents);
		String path = PATH_JOINER.join(pathComponents);
		getLock(pathLocks, path).readLock().lock();
		return new PathLock(path, this::unlockPathReadLock, this::lockDataForReading, this::lockDataForWriting);
	}

	private void unlockPathReadLock(String absolutePath) {
		List<String> pathComponents = PATH_SPLITTER.splitToList(absolutePath);
		List<String> parentPathComponents = parentPathComponents(pathComponents);
		getLock(pathLocks, absolutePath).readLock().unlock();
		removeLockIfUnused(pathLocks, absolutePath);
		unlockAncestors(parentPathComponents);
	}

	private DataLock lockDataForReading(String absolutePath) {
		getLock(dataLocks, absolutePath).readLock().lock();
		return new DataLock(absolutePath, this::unlockDataReadLock);
	}

	private void unlockDataReadLock(String absolutePath) {
		getLock(dataLocks, absolutePath).readLock().unlock();
		removeLockIfUnused(dataLocks, absolutePath);
	}

	/*
	 * Write locks:
	 */

	public PathLock lockPathForWriting(String absolutePath) {
		List<String> pathComponents = PATH_SPLITTER.splitToList(absolutePath);
		Preconditions.checkArgument(!pathComponents.isEmpty(), "path must not be empty");
		return lockPathForWriting(pathComponents);
	}

	private PathLock lockPathForWriting(List<String> pathComponents) {
		List<String> parentPathComponents = parentPathComponents(pathComponents);
		lockAncestors(parentPathComponents);
		String path = PATH_JOINER.join(pathComponents);
		getLock(pathLocks, path).writeLock().lock();
		return new PathLock(path, this::unlockPathWriteLock, this::lockDataForReading, this::lockDataForWriting);
	}

	private void unlockPathWriteLock(String absolutePath) {
		List<String> pathComponents = PATH_SPLITTER.splitToList(absolutePath);
		List<String> parentPathComponents = parentPathComponents(pathComponents);
		getLock(pathLocks, absolutePath).writeLock().unlock();
		removeLockIfUnused(pathLocks, absolutePath);
		unlockAncestors(parentPathComponents);
	}

	private DataLock lockDataForWriting(String absolutePath) {
		getLock(dataLocks, absolutePath).writeLock().lock();
		return new DataLock(absolutePath, this::unlockDataWriteLock);
	}

	private void unlockDataWriteLock(String absolutePath) {
		getLock(dataLocks, absolutePath).writeLock().unlock();
		removeLockIfUnused(dataLocks, absolutePath);
	}

	/*
	 * Support functions:
	 */

	// recusively acquire locks for parents first
	private void lockAncestors(List<String> pathComponents) {
		if (pathComponents.size() > 1) {
			lockAncestors(parentPathComponents(pathComponents));
		}
		String path = PATH_JOINER.join(pathComponents);
		getLock(pathLocks, path).readLock().lock();
	}

	// recusively release locks for children frist
	private void unlockAncestors(List<String> pathComponents) {
		String path = PATH_JOINER.join(pathComponents);
		getLock(pathLocks, path).readLock().unlock();
		removeLockIfUnused(pathLocks, path);
		if (pathComponents.size() > 1) {
			unlockAncestors(parentPathComponents(pathComponents));
		}
	}

	private ReadWriteLock getLock(ConcurrentMap<String, ReentrantReadWriteLock> map, String path) {
		return map.computeIfAbsent(path, p -> {
			LOG.trace("Creating Lock for {}", p);
			return new ReentrantReadWriteLock(true);
		});
	}

	private void removeLockIfUnused(ConcurrentMap<String, ReentrantReadWriteLock> map, String path) {
		map.compute(path, (p, l) -> {
			if (l.writeLock().tryLock()) { // if we can become the exlusive lock holder
				try {
					if (!l.hasQueuedThreads()) { // and if nobody else is waiting for a lock
						LOG.trace("Removing Lock for {}", p);
						return null; // then remove this map entry
					}
				} finally {
					l.writeLock().unlock();
				}
			}
			return l; // per default: leave map unchanged
		});
	}

	// visible for testing
	boolean isPathLocked(String absolutePath) {
		String relativePath = CharMatcher.is(PATH_SEP).trimFrom(absolutePath);
		return pathLocks.containsKey(relativePath);
	}

	private List<String> parentPathComponents(List<String> pathComponents) {
		return pathComponents.subList(0, pathComponents.size() - 1);
	}

	public static class PathLock implements AutoCloseable {

		private final String path;
		private final Consumer<String> unlockFn;
		private final Function<String, DataLock> readDataLockFn;
		private final Function<String, DataLock> writeDataLockFn;

		private PathLock(String path, Consumer<String> unlockFn, Function<String, DataLock> readDataLockFn, Function<String, DataLock> writeDataLockFn) {
			this.path = path;
			this.unlockFn = unlockFn;
			this.readDataLockFn = readDataLockFn;
			this.writeDataLockFn = writeDataLockFn;
		}

		public DataLock lockDataForReading() {
			return readDataLockFn.apply(path);
		}

		public DataLock lockDataForWriting() {
			return writeDataLockFn.apply(path);
		}

		@Override
		public void close() {
			unlockFn.accept(path);
		}
	}

	public static class DataLock implements AutoCloseable {

		private final String path;
		private final Consumer<String> unlockFn;

		private DataLock(String path, Consumer<String> unlockFn) {
			this.path = path;
			this.unlockFn = unlockFn;
		}

		@Override
		public void close() {
			unlockFn.accept(path);
		}
	}

}