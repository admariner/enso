package org.enso.table.data.column.storage;

import java.util.BitSet;
import java.util.List;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;
import org.enso.table.data.column.storage.type.AnyObjectType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.mask.SliceRange;

/**
 * Wraps a storage of any type and alters its reported storage to be of type AnyObject.
 *
 * <p>This is used to ensure that we can change a column's type to Mixed without changing its
 * underlying storage unnecessarily.
 */
public class MixedStorageFacade extends Storage<Object> {
  private final Storage<?> underlyingStorage;

  public MixedStorageFacade(Storage<?> storage) {
    underlyingStorage = storage;
  }

  @Override
  public long getSize() {
    return underlyingStorage.getSize();
  }

  @Override
  public StorageType getType() {
    return AnyObjectType.INSTANCE;
  }

  @Override
  public StorageType inferPreciseType() {
    return underlyingStorage.inferPreciseType();
  }

  @Override
  public StorageType inferPreciseTypeShrunk() {
    return underlyingStorage.inferPreciseTypeShrunk();
  }

  @Override
  public boolean isNothing(long idx) {
    return underlyingStorage.isNothing(idx);
  }

  @Override
  public Object getItemBoxed(long idx) {
    return underlyingStorage.getItemBoxed(idx);
  }

  @Override
  public boolean isBinaryOpVectorized(String name) {
    return underlyingStorage.isBinaryOpVectorized(name);
  }

  @Override
  public Storage<?> runVectorizedBinaryMap(
      String name, Object argument, MapOperationProblemAggregator problemAggregator) {
    return underlyingStorage.runVectorizedBinaryMap(name, argument, problemAggregator);
  }

  @Override
  public Storage<?> runVectorizedZip(
      String name, Storage<?> argument, MapOperationProblemAggregator problemAggregator) {
    return underlyingStorage.runVectorizedZip(name, argument, problemAggregator);
  }

  @Override
  public Storage<?> fillMissingFromPrevious(BoolStorage missingIndicator) {
    Storage<?> newStorage = underlyingStorage.fillMissingFromPrevious(missingIndicator);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> applyFilter(BitSet filterMask, int newLength) {
    Storage<?> newStorage = underlyingStorage.applyFilter(filterMask, newLength);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> applyMask(OrderMask mask) {
    Storage<?> newStorage = underlyingStorage.applyMask(mask);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> slice(int offset, int limit) {
    Storage<?> newStorage = underlyingStorage.slice(offset, limit);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<?> appendNulls(int count) {
    Storage<?> newStorage = underlyingStorage.appendNulls(count);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> slice(List<SliceRange> ranges) {
    Storage<?> newStorage = underlyingStorage.slice(ranges);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<?> tryGettingMoreSpecializedStorage() {
    return underlyingStorage.tryGettingMoreSpecializedStorage();
  }
}
