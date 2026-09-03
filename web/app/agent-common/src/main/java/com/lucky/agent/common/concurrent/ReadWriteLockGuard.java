package com.lucky.agent.common.concurrent;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 读写锁并发守卫：记忆/文件并发写防冲突。
 *
 * <p>读操作共享、写操作互斥，配合文件版本号防冲突。使用示例：</p>
 * <pre>{@code
 * lock.lockWrite();
 * try { ... } finally { lock.unlockWrite(); }
 * }</pre>
 */
public class ReadWriteLockGuard {

    private final String name;
    private final ReentrantReadWriteLock lock;

    public ReadWriteLockGuard(String name) {
        this.name = name;
        this.lock = new ReentrantReadWriteLock();
    }

    public String name() {
        return name;
    }

    public void lockRead() {
        lock.readLock().lock();
    }

    public void unlockRead() {
        lock.readLock().unlock();
    }

    public void lockWrite() {
        lock.writeLock().lock();
    }

    public void unlockWrite() {
        lock.writeLock().unlock();
    }

    /** 是否有线程持有写锁（用于监控）。 */
    public boolean isWriteLocked() {
        return lock.isWriteLocked();
    }
}
