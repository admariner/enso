package org.enso.table.data.column.storage.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.enso.base.polyglot.NumericConverter;

/**
 * Represents an underlying internal storage type that can be mapped to the Value Type that is
 * exposed to users.
 */
public sealed interface StorageType
    permits AnyObjectType,
        BigDecimalType,
        BigIntegerType,
        BooleanType,
        DateTimeType,
        DateType,
        FloatType,
        IntegerType,
        NullType,
        TextType,
        TimeOfDayType {
  /**
   * @return the StorageType that represents a given boxed item. This has special handling for
   *     floating-point values - if they represent a whole number, they will be treated as integers.
   */
  static StorageType forBoxedItem(Object item) {
    if (NumericConverter.isCoercibleToLong(item)) {
      return IntegerType.INT_64;
    }

    if (NumericConverter.isFloatLike(item)) {
      double value = NumericConverter.coerceToDouble(item);
      if (value % 1.0 == 0.0 && IntegerType.INT_64.fits(value)) {
        return IntegerType.INT_64;
      }

      return FloatType.FLOAT_64;
    }

    return switch (item) {
      case String s -> TextType.VARIABLE_LENGTH;
      case BigDecimal i -> BigDecimalType.INSTANCE;
      case BigInteger i -> BigIntegerType.INSTANCE;
      case Boolean b -> BooleanType.INSTANCE;
      case LocalDate d -> DateType.INSTANCE;
      case LocalTime t -> TimeOfDayType.INSTANCE;
      case LocalDateTime d -> DateTimeType.INSTANCE;
      case ZonedDateTime d -> DateTimeType.INSTANCE;
      default -> AnyObjectType.INSTANCE;
    };
  }

  /**
   * @return true if the storage type is numeric.
   */
  boolean isNumeric();

  /**
   * @return true if the storage type has a date part.
   */
  boolean hasDate();

  /**
   * @return true if the storage type has a time part.
   */
  boolean hasTime();
}
