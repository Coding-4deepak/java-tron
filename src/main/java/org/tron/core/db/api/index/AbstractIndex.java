package org.tron.core.db.api.index;

import com.google.common.collect.Iterables;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import java.util.Iterator;
import org.tron.core.capsule.ProtoCapsule;
import org.tron.core.db.TronDatabase;
import org.tron.core.db.api.index.Index.Iface;
import org.tron.core.db.common.WrappedByteArray;
import org.tron.core.db.common.WrappedResultSet;

public abstract class AbstractIndex<E extends ProtoCapsule, T> implements Iterable<T>, Iface {

  protected TronDatabase<E> database;
  protected ConcurrentIndexedCollection<WrappedByteArray> index;

  public AbstractIndex() {
    index = new ConcurrentIndexedCollection<>();
  }

  public AbstractIndex(Persistence<WrappedByteArray, ? extends Comparable> persistence) {
    index = new ConcurrentIndexedCollection<>(persistence);
  }

  public boolean update(WrappedByteArray bytes) {
    return update(bytes);
  }

  public boolean update(byte[] bytes) {
    return add(WrappedByteArray.of(bytes));
  }

  @Override
  public String getName() {
    return this.getClass().getSimpleName();
  }

  protected T getObject(final byte[] key) {
    try {
      @SuppressWarnings("unchecked")
      T t = (T) database.get(key).getInstance();
      return t;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected T getObject(final WrappedByteArray byteArray) {
    return getObject(byteArray.getBytes());
  }

  protected void fill() {
    database.forEach(e -> index.add(WrappedByteArray.of(e.getKey())));
  }

  public boolean add(byte[] bytes) {
    return add(WrappedByteArray.of(bytes));
  }

  public boolean add(WrappedByteArray bytes) {
    return index.add(bytes);
  }

  public boolean remove(byte[] bytes) {
    return remove(WrappedByteArray.of(bytes));
  }

  public boolean remove(WrappedByteArray bytes) {
    return index.remove(bytes);
  }

  public long size() {
    return index.size();
  }

  public ResultSet<T> retrieve(Query<WrappedByteArray> query) {
    ResultSet<WrappedByteArray> resultSet = index.retrieve(query);
    return new WrappedResultSet<T>(resultSet) {
      @Override
      public Iterator<T> iterator() {
        return Iterables.transform(resultSet, AbstractIndex.this::getObject).iterator();
      }
    };
  }

  public ResultSet<T> retrieve(Query<WrappedByteArray> query, QueryOptions options) {
    ResultSet<WrappedByteArray> resultSet = index.retrieve(query, options);
    return new WrappedResultSet<T>(resultSet) {
      @Override
      public Iterator<T> iterator() {
        return Iterables.transform(resultSet, AbstractIndex.this::getObject).iterator();
      }
    };
  }

  @Override
  public Iterator<T> iterator() {
    return Iterables.transform(index, AbstractIndex.this::getObject).iterator();
  }

}
