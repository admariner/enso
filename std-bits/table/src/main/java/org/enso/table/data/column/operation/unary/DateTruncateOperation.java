package org.enso.table.data.column.operation.unary;

import java.time.ZonedDateTime;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.operation.UnaryOperation;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;
import org.enso.table.data.column.storage.ColumnStorage;
import org.enso.table.data.column.storage.type.DateTimeType;

public class DateTruncateOperation extends AbstractUnaryOperation {
  public static String TRUNCATE = "truncate";
  public static final UnaryOperation TRUNCATE_INSTANCE = new DateTruncateOperation();

  private DateTruncateOperation() {
    super(TRUNCATE, true);
  }

  @Override
  public boolean canApply(ColumnStorage<?> storage) {
    return storage.getType() == DateTimeType.INSTANCE;
  }

  @Override
  protected Builder createBuilder(
      ColumnStorage<?> storage, MapOperationProblemAggregator problemAggregator) {
    return Builder.getForDate(storage.getSize());
  }

  @Override
  protected void applyObjectRow(
      Object value, Builder builder, MapOperationProblemAggregator problemAggregator) {
    if (value instanceof ZonedDateTime zonedDateTime) {
      builder.append(zonedDateTime.toLocalDate());
    } else {
      throw new IllegalArgumentException(
          "Unsupported type: Expected a Date_Time, got " + value.getClass());
    }
  }
}
