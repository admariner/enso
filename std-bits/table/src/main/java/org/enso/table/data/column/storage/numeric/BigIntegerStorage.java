package org.enso.table.data.column.storage.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.operation.map.MapOperationStorage;
import org.enso.table.data.column.operation.map.numeric.arithmetic.AddOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.DivideOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.MaxOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.MinOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.ModOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.MulOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.PowerOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.SubOp;
import org.enso.table.data.column.operation.map.numeric.comparisons.EqualsComparison;
import org.enso.table.data.column.operation.map.numeric.comparisons.GreaterComparison;
import org.enso.table.data.column.operation.map.numeric.comparisons.GreaterOrEqualComparison;
import org.enso.table.data.column.operation.map.numeric.comparisons.LessComparison;
import org.enso.table.data.column.operation.map.numeric.comparisons.LessOrEqualComparison;
import org.enso.table.data.column.operation.map.numeric.isin.BigIntegerIsInOp;
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.type.BigIntegerType;
import org.enso.table.data.column.storage.type.IntegerType;
import org.enso.table.data.column.storage.type.StorageType;

public class BigIntegerStorage extends SpecializedStorage<BigInteger> {
  /**
   * @param data the underlying data
   */
  public BigIntegerStorage(BigInteger[] data) {
    super(BigIntegerType.INSTANCE, data, makeOps());
  }

  protected static MapOperationStorage<BigInteger, SpecializedStorage<BigInteger>> makeOps() {
    MapOperationStorage<BigInteger, SpecializedStorage<BigInteger>> ops =
        new MapOperationStorage<>();
    return ops.add(new AddOp<>())
        .add(new SubOp<>())
        .add(new MulOp<>())
        .add(new DivideOp<>())
        .add(new ModOp<>())
        .add(new PowerOp<>())
        .add(new MinOp<>())
        .add(new MaxOp<>())
        .add(new LessComparison<>())
        .add(new LessOrEqualComparison<>())
        .add(new EqualsComparison<>())
        .add(new GreaterOrEqualComparison<>())
        .add(new GreaterComparison<>())
        .add(new BigIntegerIsInOp<>());
  }

  public static BigIntegerStorage makeEmpty(long size) {
    int intSize = Builder.checkSize(size);
    return new BigIntegerStorage(new BigInteger[intSize]);
  }

  @Override
  protected SpecializedStorage<BigInteger> newInstance(BigInteger[] data) {
    return new BigIntegerStorage(data);
  }

  @Override
  protected BigInteger[] newUnderlyingArray(int size) {
    return new BigInteger[size];
  }

  private long cachedMaxPrecisionStored = -1;

  public long getMaxPrecisionStored() {
    if (cachedMaxPrecisionStored < 0) {
      long maxPrecision = 0;
      for (int i = 0; i < getSize(); i++) {
        BigInteger value = data[i];
        if (value == null) {
          continue;
        }

        BigDecimal asDecimal = new BigDecimal(value);
        assert asDecimal.scale() == 0;
        int precision = asDecimal.precision();
        if (precision > maxPrecision) {
          maxPrecision = precision;
        }
      }

      cachedMaxPrecisionStored = maxPrecision;
    }

    return cachedMaxPrecisionStored;
  }

  private StorageType inferredType = null;

  @Override
  public StorageType inferPreciseType() {
    if (inferredType == null) {
      boolean allFitInLong = true;
      int visitedCount = 0;

      for (int i = 0; i < getSize(); i++) {
        BigInteger value = data[i];
        if (value == null) {
          continue;
        }

        visitedCount++;
        boolean fitsInLong = IntegerType.INT_64.fits(value);
        if (!fitsInLong) {
          allFitInLong = false;
          break;
        }
      }

      inferredType =
          (allFitInLong && visitedCount > 0) ? IntegerType.INT_64 : BigIntegerType.INSTANCE;
    }

    return inferredType;
  }

  @Override
  public StorageType inferPreciseTypeShrunk() {
    StorageType preciseType = inferPreciseType();
    if (preciseType instanceof IntegerType) {
      return findSmallestIntegerTypeThatFits();
    }

    return preciseType;
  }

  private StorageType findSmallestIntegerTypeThatFits() {
    // This method assumes that all values _do_ fit in some integer type.
    assert inferredType instanceof IntegerType;

    final BigIntegerStorage parent = this;

    // We create a Long storage that gets values by converting our storage.
    ComputedNullableLongStorage longAdapter =
        new ComputedNullableLongStorage((int) getSize()) {
          @Override
          protected Long computeItem(int idx) {
            BigInteger bigInteger = parent.getItemBoxed(idx);
            if (bigInteger == null) {
              return null;
            }

            return bigInteger.longValueExact();
          }
        };

    // And rely on its shrinking logic.
    return longAdapter.inferPreciseTypeShrunk();
  }
}
