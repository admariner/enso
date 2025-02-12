package org.enso.table.data.column.storage.numeric;

import java.math.BigDecimal;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.operation.map.MapOperationStorage;
import org.enso.table.data.column.operation.map.numeric.BigDecimalRoundOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.AddOp;
import org.enso.table.data.column.operation.map.numeric.arithmetic.BigDecimalDivideOp;
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
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.type.BigDecimalType;

public final class BigDecimalStorage extends SpecializedStorage<BigDecimal> {
  /**
   * @param data the underlying data
   */
  public BigDecimalStorage(BigDecimal[] data) {
    super(BigDecimalType.INSTANCE, data, buildOps());
  }

  public static BigDecimalStorage makeEmpty(long size) {
    int intSize = Builder.checkSize(size);
    return new BigDecimalStorage(new BigDecimal[intSize]);
  }

  private static MapOperationStorage<BigDecimal, SpecializedStorage<BigDecimal>> buildOps() {
    MapOperationStorage<BigDecimal, SpecializedStorage<BigDecimal>> ops =
        new MapOperationStorage<>();
    return ops.add(new AddOp<>())
        .add(new SubOp<>())
        .add(new MulOp<>())
        .add(new BigDecimalDivideOp<>())
        .add(new BigDecimalRoundOp())
        .add(new PowerOp<>())
        .add(new ModOp<>())
        .add(new MinOp<>())
        .add(new MaxOp<>())
        .add(new LessComparison<>())
        .add(new LessOrEqualComparison<>())
        .add(new EqualsComparison<>())
        .add(new GreaterOrEqualComparison<>())
        .add(new GreaterComparison<>());
  }

  @Override
  protected SpecializedStorage<BigDecimal> newInstance(BigDecimal[] data) {
    return new BigDecimalStorage(data);
  }

  @Override
  protected BigDecimal[] newUnderlyingArray(int size) {
    return new BigDecimal[size];
  }
}
