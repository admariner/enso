package org.enso.table.data.column.operation.map.numeric.arithmetic;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;

public interface NumericBinaryOpDefinition {
  double doDouble(double a, double b, long ix, MapOperationProblemAggregator problemAggregator);

  Long doLong(long a, long b, long ix, MapOperationProblemAggregator problemAggregator);

  BigInteger doBigInteger(
      BigInteger a, BigInteger b, long ix, MapOperationProblemAggregator problemAggregator);

  BigDecimal doBigDecimal(
      BigDecimal a, BigDecimal b, long ix, MapOperationProblemAggregator problemAggregator);
}
