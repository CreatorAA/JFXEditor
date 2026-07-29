package org.pigeonshouse.javafx.editor.editor.decoration;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全的装饰容器：读写锁保护主列表与行/id/类型三重索引。
 *
 * <p>所有读操作返回防御性拷贝，可安全遍历。变更事件在持有写锁时
 * 同步回调监听器——<strong>监听器内不得再调用本模型的写方法</strong>
 * （会重入），且回调线程即写入线程。</p>
 *
 * @see Decoration
 * @see DecorationChange
 */
public class DecorationModel {

    /** 全部装饰的主列表（插入序）。 */
    private final List<Decoration> decorations;
    /** 行号索引：装饰覆盖的每一行都建条目。 */
    private final Map<Integer, List<Decoration>> lineIndex;
    /** id 索引：仅收录有 id 的装饰。 */
    private final Map<String, Decoration> idIndex;
    /** 类型索引。 */
    private final Map<DecorationType, List<Decoration>> typeIndex;
    /** 变更监听器列表（增删也走写锁）。 */
    private final List<DecorationChangeListener> listeners;
    /** 保护上述全部结构的读写锁。 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DecorationModel() {
        this.decorations = new ArrayList<>();
        this.lineIndex = new HashMap<>();
        this.idIndex = new HashMap<>();
        this.typeIndex = new HashMap<>();
        this.listeners = new ArrayList<>();
    }

    /**
     * 添加单个装饰并发送 ADDED 事件。
     *
     * @param decoration 待添加装饰，不可为 {@code null}
     * @throws NullPointerException 传入 {@code null} 时
     */
    public void addDecoration(Decoration decoration) {
        lock.writeLock().lock();
        try {
            Objects.requireNonNull(decoration, "decoration cannot be null");
            decorations.add(decoration);
            indexDecoration(decoration);
            fireChange(DecorationChange.added(decoration.startLine(), decoration.endLine()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量添加装饰，仅发一次覆盖 {@code min..max} 行的 ADDED 事件。
     *
     * @param decos 待添加装饰集合；空集合时无操作不发事件
     */
    public void addDecorations(Collection<Decoration> decos) {
        lock.writeLock().lock();
        try {
            if (decos.isEmpty()) return;
            int minLine = Integer.MAX_VALUE;
            int maxLine = Integer.MIN_VALUE;
            for (Decoration d : decos) {
                decorations.add(d);
                indexDecoration(d);
                minLine = Math.min(minLine, d.startLine());
                maxLine = Math.max(maxLine, d.endLine());
            }
            fireChange(DecorationChange.added(minLine, maxLine));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按 id 移除装饰。
     *
     * @param id 装饰唯一标识
     * @return 存在并移除时返回 {@code true}；不存在时返回 {@code false} 不发事件
     */
    public boolean removeById(String id) {
        lock.writeLock().lock();
        try {
            Decoration d = idIndex.remove(id);
            if (d == null) return false;
            decorations.remove(d);
            unindexDecoration(d);
            fireChange(DecorationChange.removed(d.startLine(), d.endLine()));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 移除指定行上的全部装饰。
     *
     * @param line 目标行号
     * @return 移除数量；为 0 时不发事件
     */
    public int removeOnLine(int line) {
        lock.writeLock().lock();
        try {
            List<Decoration> onLine = new ArrayList<>(getDecorationsOnLineInternal(line));
            for (Decoration d : onLine) {
                if (d.id() != null) {
                    idIndex.remove(d.id());
                }
                decorations.remove(d);
                unindexDecoration(d);
            }
            if (!onLine.isEmpty()) {
                fireChange(DecorationChange.removed(line, line));
            }
            return onLine.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 清空全部装饰与索引，发送 CLEARED 事件。 */
    public void clearAll() {
        lock.writeLock().lock();
        try {
            decorations.clear();
            lineIndex.clear();
            idIndex.clear();
            typeIndex.clear();
            fireChange(DecorationChange.cleared());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 替换装饰：有同 id 旧装饰时先移除再加入；无 id 时等价于新增。
     * 发送 REPLACED 事件。
     *
     * @param decoration 新装饰
     */
    public void replaceDecoration(Decoration decoration) {
        lock.writeLock().lock();
        try {
            if (decoration.id() != null) {
                Decoration old = idIndex.get(decoration.id());
                if (old != null) {
                    decorations.remove(old);
                    unindexDecoration(old);
                }
            }
            decorations.add(decoration);
            indexDecoration(decoration);
            fireChange(DecorationChange.replaced(decoration.startLine(), decoration.endLine()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return 全部装饰的不可变快照拷贝 */
    public List<Decoration> getDecorations() {
        lock.readLock().lock();
        try {
            return List.copyOf(decorations);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回指定行上的装饰快照。
     *
     * @param line 目标行号
     * @return 该行装饰的不可变拷贝；无装饰时返回空列表
     */
    public List<Decoration> getDecorationsOnLine(int line) {
        lock.readLock().lock();
        try {
            List<Decoration> lineDecos = getDecorationsOnLineInternal(line);
            return lineDecos == null ? List.of() : List.copyOf(lineDecos);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 读取行索引内部列表（调用方需持锁），可能返回 {@code null}。 */
    private List<Decoration> getDecorationsOnLineInternal(int line) {
        return lineIndex.get(line);
    }

    /**
     * 返回指定类型的全部装饰快照。
     *
     * @param type 装饰类型
     * @return 该类型装饰的不可变拷贝；无匹配时返回空列表
     */
    public List<Decoration> getByType(DecorationType type) {
        lock.readLock().lock();
        try {
            List<Decoration> indexed = typeIndex.get(type);
            return indexed == null ? List.of() : List.copyOf(indexed);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按 id 查找装饰。
     *
     * @param id 装饰唯一标识；{@code null} 入参直接返回 {@code null}
     * @return 对应装饰；不存在时返回 {@code null}
     */
    public Decoration getById(String id) {
        lock.readLock().lock();
        try {
            if (id == null) return null;
            return idIndex.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 注册变更监听器（回调时持有写锁，见类注释警告）。
     *
     * @param listener 监听器，不可为 {@code null}
     */
    public void addDecorationListener(DecorationChangeListener listener) {
        lock.writeLock().lock();
        try {
            listeners.add(Objects.requireNonNull(listener));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 移除已注册的变更监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    public void removeDecorationListener(DecorationChangeListener listener) {
        lock.writeLock().lock();
        try {
            listeners.remove(listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return 当前装饰总数 */
    public int size() {
        lock.readLock().lock();
        try {
            return decorations.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** @return 无任何装饰时返回 {@code true} */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return decorations.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 把装饰写入三重索引（id/覆盖行/类型），需在写锁内调用。 */
    private void indexDecoration(Decoration d) {
        if (d.id() != null) {
            idIndex.put(d.id(), d);
        }
        for (int line = d.startLine(); line <= d.endLine(); line++) {
            lineIndex.computeIfAbsent(line, k -> new ArrayList<>()).add(d);
        }
        typeIndex.computeIfAbsent(d.type(), k -> new ArrayList<>()).add(d);
    }

    /** 从行/类型索引中移除装饰，空列表随之移除映射键；需在写锁内调用。 */
    private void unindexDecoration(Decoration d) {
        for (int line = d.startLine(); line <= d.endLine(); line++) {
            List<Decoration> list = lineIndex.get(line);
            if (list != null) {
                list.remove(d);
                if (list.isEmpty()) {
                    lineIndex.remove(line);
                }
            }
        }
        List<Decoration> typedList = typeIndex.get(d.type());
        if (typedList != null) {
            typedList.remove(d);
            if (typedList.isEmpty()) {
                typeIndex.remove(d.type());
            }
        }
    }

    /** 在持有写锁的情况下同步回调全部监听器。 */
    private void fireChange(DecorationChange change) {
        for (DecorationChangeListener listener : listeners) {
            listener.decorationsChanged(change);
        }
    }
}