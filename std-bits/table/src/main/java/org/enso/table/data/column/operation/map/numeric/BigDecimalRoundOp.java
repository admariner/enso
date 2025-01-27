package org.enso.table.data.column.operation.map.numeric;

import java.math.BigDecimal;
import org.enso.base.numeric.Decimal_Utils;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;
import org.enso.table.data.column.operation.map.TernaryMapOperation;
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.error.UnexpectedTypeException;
import org.graalvm.polyglot.Context;

/** An operation rounding BigDecimals. */
public class BigDecimalRoundOp
    extends TernaryMapOperation<BigDecimal, SpecializedStorage<BigDecimal>> {

  static final int ROUND_MIN_DECIMAL_PLACES = -15;
  static final int ROUND_MAX_DECIMAL_PLACES = 15;

  public BigDecimalRoundOp() {
    super(Storage.Maps.ROUND);
  }

  @Override
  public Storage<?> runTernaryMap(
      SpecializedStorage<BigDecimal> storage,
      Object decimalPlacesObject,
      Object useBankersObject,
      MapOperationProblemAggregator problemAggregator) {
    if (!(decimalPlacesObject instanceof Long decimalPlaces)) {
      throw new UnexpectedTypeException("a long.");
    }

    if (!(useBankersObject instanceof Boolean useBankers)) {
      throw new UnexpectedTypeException("a boolean.");
    }

    assert decimalPlaces >= ROUND_MIN_DECIMAL_PLACES && decimalPlaces <= ROUND_MAX_DECIMAL_PLACES;
    int decimalPlacesInt = (int) decimalPlaces.longValue();

    Builder builder = Builder.getForBigDecimal(storage.getSize());
    Context context = Context.getCurrent();

    for (long i = 0; i < storage.getSize(); i++) {
      if (!storage.isNothing(i)) {
        BigDecimal value = storage.getItemBoxed(i);
        BigDecimal result = Decimal_Utils.round(value, decimalPlacesInt, useBankers);
        builder.append(result);
      } else {
        builder.appendNulls(1);
      }
      context.safepoint();
    }

    return builder.seal();
  }
}
