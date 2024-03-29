package com.bumptech.glide.load.engine.bitmap_recycle;

import androidx.annotation.Nullable;
import com.bumptech.glide.util.Synthetic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Similar to {@link java.util.LinkedHashMap} when access ordered except that it is access ordered
 * on groups of bitmaps rather than individual objects. The idea is to be able to find the LRU
 * bitmap size, rather than the LRU bitmap object. We can then remove bitmaps from the least
 * recently used size of bitmap when we need to reduce our cache size.
 *
 * <p>For the purposes of the LRU, we count gets for a particular size of bitmap as an access, even
 * if no bitmaps of that size are present. We do not count addition or removal of bitmaps as an
 * access.
 */
// * GroupedLinkedMap 来缓存 Bitmap，它是类似于 HashMap，不过和 HashMap 不同的是：HashMap 会移除相同 Key 的数据；
// * GroupedLinkedMap 不会移除相同 Key 的数据，而是把相同 Key 的数据以列表的形式串联起来。HashMap 的 get()
// * 方法也不会移除原来的数据，而 GroupedLinkedMap 会移除获取到的数据，
//，Glide缓存Bitmap，是利用Bitmap对象的内存sizhe和config作为判断依据，如果两者相等，则说明Key相等。
// 而在我们项目的实际开发中，一般都有一套规范，存在大量尺寸相等，配置相等的图，如果我们利用LinkedHashMap来实现，
// 那么每种Key只能保存一个，也就是大小配置相等的Bitmap只能保存一份，这是不利于缓存的。如果命中不了缓存的Bitmap就无法复用，
// 就要重新创建Bitmap对象。
class GroupedLinkedMap<K extends Poolable, V> {
  private final LinkedEntry<K, V> head = new LinkedEntry<>();
  private final Map<K, LinkedEntry<K, V>> keyToEntry = new HashMap<>();

  //存放数据时，如果具有相同 key，不会替换 HashMap 中的数据，而是取出已经存在的数据，然后把 value 值添加到对应的 LinkedEntry 类中。
  // 这个就是和 HashMap 最大的区别，HashMap 每次遇到相同 key 的数据，会替换并返回旧值。
  public void put(K key, V value) {
    // 获取对应的 LinkedEntry
    LinkedEntry<K, V> entry = keyToEntry.get(key);

    if (entry == null) {
      // LinkedEntry 为空
      // 创建一个新的 LinkedEntry 对象。
      entry = new LinkedEntry<>(key);
      // 将当前 entry 插入到链表尾部
      makeTail(entry);
      // 将当前 entry 添加到 Map 中
      keyToEntry.put(key, entry);
    } else {
      key.offer();
    }

    entry.add(value);
  }

  //到 HashMap 中获取对应 key 的数据，如果没有，就先创建一个记录保存，只是这个记录 LinkedEntry 只有 key，没有 value 值而已
  //把找到或者生成的 LinkedEntry 放入链表的头部位置
  //返回 LinkedEntry 中数组集合数据的最后一个，如果没有则返回 null
  @Nullable
  public V get(K key) {
    // 获取缓存
    LinkedEntry<K, V> entry = keyToEntry.get(key);
    if (entry == null) {
      entry = new LinkedEntry<>(key);
      // 将当前 entry 添加到 Map 中
      keyToEntry.put(key, entry);
    } else {
      //放入队列
      key.offer();
    }

    makeHead(entry);

    return entry.removeLast();
  }

  @Nullable
  public V removeLast() {
    // 获取最后一个 LinkedEntry
    LinkedEntry<K, V> last = head.prev;

    while (!last.equals(head)) {
      // 通过 LinkedEntry#removeLast() 方法移除最后一个元素
      V removed = last.removeLast();
      if (removed != null) {
        // 移除的数据不为空直接返回
        return removed;
      } else {
        // We will clean up empty lru entries since they are likely to have been one off or
        // unusual sizes and
        // are not likely to be requested again so the gc thrash should be minimal. Doing so will
        // speed up our
        // removeLast operation in the future and prevent our linked list from growing to
        // arbitrarily large
        // sizes.

        // 移除的数据为空表示，LinkedEntry 已经为空了，需要从链表中移除，也需要从 HashMap 中移除
        removeEntry(last);
        keyToEntry.remove(last.key);
        // 回收对应的 Key
        last.key.offer();
      }

      last = last.prev;
    }

    return null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("GroupedLinkedMap( ");
    LinkedEntry<K, V> current = head.next;
    boolean hadAtLeastOneItem = false;
    while (!current.equals(head)) {
      hadAtLeastOneItem = true;
      sb.append('{').append(current.key).append(':').append(current.size()).append("}, ");
      current = current.next;
    }
    if (hadAtLeastOneItem) {
      sb.delete(sb.length() - 2, sb.length());
    }
    return sb.append(" )").toString();
  }

  // Make the entry the most recently used item.
  private void makeHead(LinkedEntry<K, V> entry) {
    removeEntry(entry);
    entry.prev = head;
    entry.next = head.next;
    updateEntry(entry);
  }

  // Make the entry the least recently used item.
  private void makeTail(LinkedEntry<K, V> entry) {
    // 先移除当前节点
    removeEntry(entry);
    // 当前节点的前一个节点指向 head 的前一个节点
    entry.prev = head.prev;
    // 当前节点的下一个节点指向 head
    entry.next = head;
    // 更新当前节点的前后节点的状态
    updateEntry(entry);
  }

  private static <K, V> void updateEntry(LinkedEntry<K, V> entry) {
    entry.next.prev = entry;
    entry.prev.next = entry;
  }

  /**
   * 先移除当前节点
   */
  private static <K, V> void removeEntry(LinkedEntry<K, V> entry) {
    entry.prev.next = entry.next;
    entry.next.prev = entry.prev;
  }

  //一个双向的环状链表
  private static class LinkedEntry<K, V> {
    @Synthetic final K key;
    private List<V> values;
    LinkedEntry<K, V> next;
    LinkedEntry<K, V> prev;

    // Used only for the first item in the list which we will treat specially and which will not
    // contain a value.
    LinkedEntry() {
      this(null);
    }

    LinkedEntry(K key) {
      next = prev = this;
      this.key = key;
    }

    @Nullable
    public V removeLast() {
      final int valueSize = size();
      return valueSize > 0 ? values.remove(valueSize - 1) : null;
    }

    public int size() {
      return values != null ? values.size() : 0;
    }

    public void add(V value) {
      if (values == null) {
        values = new ArrayList<>();
      }
      values.add(value);
    }
  }
}
