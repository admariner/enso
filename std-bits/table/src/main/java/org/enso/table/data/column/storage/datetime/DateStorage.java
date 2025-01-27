package org.enso.table.data.column.storage.datetime;

import java.time.LocalDate;
import org.enso.base.CompareException;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.operation.map.MapOperationStorage;
import org.enso.table.data.column.operation.map.datetime.DateTimeIsInOp;
import org.enso.table.data.column.operation.map.datetime.TimeLikeBinaryOpReturningBoolean;
import org.enso.table.data.column.operation.map.datetime.TimeLikeCoalescingOperation;
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.type.DateType;

public final class DateStorage extends SpecializedStorage<LocalDate> {
  /**
   * @param data the underlying data
   */
  public DateStorage(LocalDate[] data) {
    super(DateType.INSTANCE, data, buildOps());
  }

  private static MapOperationStorage<LocalDate, SpecializedStorage<LocalDate>> buildOps() {
    MapOperationStorage<LocalDate, SpecializedStorage<LocalDate>> t = new MapOperationStorage<>();
    t.add(new DateTimeIsInOp<>(LocalDate.class));
    t.add(
        new TimeLikeBinaryOpReturningBoolean<>(Maps.EQ, LocalDate.class) {
          @Override
          protected boolean doOperation(LocalDate a, LocalDate b) {
            return a.isEqual(b);
          }

          @Override
          protected boolean doOther(LocalDate a, Object b) {
            return false;
          }
        });
    t.add(
        new DateComparisonOp(Maps.LT) {
          @Override
          protected boolean doOperation(LocalDate a, LocalDate b) {
            return a.isBefore(b);
          }
        });
    t.add(
        new DateComparisonOp(Maps.LTE) {
          @Override
          protected boolean doOperation(LocalDate a, LocalDate b) {
            return !a.isAfter(b);
          }
        });
    t.add(
        new DateComparisonOp(Maps.GT) {
          @Override
          protected boolean doOperation(LocalDate a, LocalDate b) {
            return a.isAfter(b);
          }
        });
    t.add(
        new DateComparisonOp(Maps.GTE) {
          @Override
          protected boolean doOperation(LocalDate a, LocalDate b) {
            return !a.isBefore(b);
          }
        });
    t.add(
        new TimeLikeCoalescingOperation<>(Maps.MIN, LocalDate.class) {
          @Override
          protected Builder createOutputBuilder(long size) {
            return Builder.getForDate(size);
          }

          @Override
          protected LocalDate doOperation(LocalDate a, LocalDate b) {
            return a.isBefore(b) ? a : b;
          }
        });
    t.add(
        new TimeLikeCoalescingOperation<>(Maps.MAX, LocalDate.class) {
          @Override
          protected Builder createOutputBuilder(long size) {
            return Builder.getForDate(size);
          }

          @Override
          protected LocalDate doOperation(LocalDate a, LocalDate b) {
            return a.isAfter(b) ? a : b;
          }
        });
    return t;
  }

  @Override
  protected SpecializedStorage<LocalDate> newInstance(LocalDate[] data) {
    return new DateStorage(data);
  }

  @Override
  protected LocalDate[] newUnderlyingArray(int size) {
    return new LocalDate[size];
  }

  private abstract static class DateComparisonOp
      extends TimeLikeBinaryOpReturningBoolean<LocalDate> {
    public DateComparisonOp(String name) {
      super(name, LocalDate.class);
    }

    @Override
    protected boolean doOther(LocalDate a, Object b) {
      throw new CompareException(a, b);
    }
  }
}
