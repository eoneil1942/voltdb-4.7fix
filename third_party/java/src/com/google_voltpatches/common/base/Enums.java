/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google_voltpatches.common.base;

import static com.google_voltpatches.common.base.Preconditions.checkNotNull;

import com.google_voltpatches.common.annotations.Beta;
import com.google_voltpatches.common.annotations.GwtCompatible;
import com.google_voltpatches.common.annotations.GwtIncompatible;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation_voltpatches.Nullable;

/**
 * Utility methods for working with {@link Enum} instances.
 *
 * @author Steve McKay
 *
 * @since 9.0
 */
@GwtCompatible(emulated = true)
@Beta
public final class Enums {

  private Enums() {}

  /**
   * Returns the {@link Field} in which {@code enumValue} is defined. For example, to get the
   * {@code Description} annotation on the {@code GOLF} constant of enum {@code Sport}, use
   * {@code Enums.getField(Sport.GOLF).getAnnotation(Description.class)}.
   *
   * @since 12.0
   */
  @GwtIncompatible("reflection")
  public static Field getField(Enum<?> enumValue) {
    Class<?> clazz = enumValue.getDeclaringClass();
    try {
      return clazz.getDeclaredField(enumValue.name());
    } catch (NoSuchFieldException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Returns a {@link Function} that maps an {@link Enum} name to the associated {@code Enum}
   * constant. The {@code Function} will return {@code null} if the {@code Enum} constant
   * does not exist.
   *
   * @param enumClass the {@link Class} of the {@code Enum} declaring the constant values
   * @deprecated Use {@link Enums#stringConverter} instead. Note that the string converter has
   *     slightly different behavior: it throws {@link IllegalArgumentException} if the enum
   *     constant does not exist rather than returning {@code null}. It also converts {@code null}
   *     to {@code null} rather than throwing {@link NullPointerException}. This method is
   *     scheduled for removal in Guava 18.0.
   */
  @Deprecated
  public static <T extends Enum<T>> Function<String, T> valueOfFunction(
      Class<T> enumClass) {
    return new ValueOfFunction<T>(enumClass);
  }

  /**
   * A {@link Function} that maps an {@link Enum} name to the associated constant, or {@code null}
   * if the constant does not exist.
   */
  private static final class ValueOfFunction<T extends Enum<T>>
      implements Function<String, T>, Serializable {

    private final Class<T> enumClass;

    private ValueOfFunction(Class<T> enumClass) {
      this.enumClass = checkNotNull(enumClass);
    }

    @Override
    public T apply(String value) {
      try {
        return Enum.valueOf(enumClass, value);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    @Override public boolean equals(@Nullable Object obj) {
      return obj instanceof ValueOfFunction && enumClass.equals(((ValueOfFunction) obj).enumClass);
    }

    @Override public int hashCode() {
      return enumClass.hashCode();
    }

    @Override public String toString() {
      return "Enums.valueOf(" + enumClass + ")";
    }

    private static final long serialVersionUID = 0;
  }

  /**
   * Returns an optional enum constant for the given type, using {@link Enum#valueOf}. If the
   * constant does not exist, {@link Optional#absent} is returned. A common use case is for parsing
   * user input or falling back to a default enum constant. For example,
   * {@code Enums.getIfPresent(Country.class, countryInput).or(Country.DEFAULT);}
   *
   * @since 12.0
   */
  public static <T extends Enum<T>> Optional<T> getIfPresent(
      Class<T> enumClass, String value) {
    checkNotNull(enumClass);
    checkNotNull(value);
    return Platform.getEnumIfPresent(enumClass, value);
  }

  @GwtIncompatible("java.lang.ref.WeakReference")
  private static final Map<Class<? extends Enum<?>>, Map<String, WeakReference<? extends Enum<?>>>>
      enumConstantCache = new WeakHashMap
              <Class<? extends Enum<?>>, Map<String, WeakReference<? extends Enum<?>>>>();

  @GwtIncompatible("java.lang.ref.WeakReference")
  private static <T extends Enum<T>> Map<String, WeakReference<? extends Enum<?>>> populateCache(
      Class<T> enumClass) {
    Map<String, WeakReference<? extends Enum<?>>> result
        = new HashMap<String, WeakReference<? extends Enum<?>>>();
    for (T enumInstance : EnumSet.allOf(enumClass)) {
      result.put(enumInstance.name(), new WeakReference<Enum<?>>(enumInstance));
    }
    enumConstantCache.put(enumClass, result);
    return result;
  }

  @GwtIncompatible("java.lang.ref.WeakReference")
  static <T extends Enum<T>> Map<String, WeakReference<? extends Enum<?>>> getEnumConstants(
      Class<T> enumClass) {
    synchronized (enumConstantCache) {
      Map<String, WeakReference<? extends Enum<?>>> constants =
          enumConstantCache.get(enumClass);
      if (constants == null) {
        constants = populateCache(enumClass);
      }
      return constants;
    }
  }

  /**
   * Returns a converter that converts between strings and {@code enum} values of type
   * {@code enumClass} using {@link Enum#valueOf(Class, String)} and {@link Enum#name()}. The
   * converter will throw an {@code IllegalArgumentException} if the argument is not the name of
   * any enum constant in the specified enum.
   *
   * @since 16.0
   */
  public static <T extends Enum<T>> Converter<String, T> stringConverter(final Class<T> enumClass) {
    return new StringConverter<T>(enumClass);
  }

  private static final class StringConverter<T extends Enum<T>>
      extends Converter<String, T> implements Serializable {

    private final Class<T> enumClass;

    StringConverter(Class<T> enumClass) {
      this.enumClass = checkNotNull(enumClass);
    }

    @Override
    protected T doForward(String value) {
      return Enum.valueOf(enumClass, value);
    }

    @Override
    protected String doBackward(T enumValue) {
      return enumValue.name();
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (object instanceof StringConverter) {
        StringConverter<?> that = (StringConverter<?>) object;
        return this.enumClass.equals(that.enumClass);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return enumClass.hashCode();
    }

    @Override
    public String toString() {
      return "Enums.stringConverter(" + enumClass.getName() + ".class)";
    }

    private static final long serialVersionUID = 0L;
  }
}
