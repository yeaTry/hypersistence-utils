package io.hypersistence.utils.hibernate.type.range.guava;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import io.hypersistence.utils.hibernate.type.ImmutableType;
import io.hypersistence.utils.hibernate.util.ReflectionUtils;
import org.hibernate.HibernateException;
import org.hibernate.annotations.common.reflection.XProperty;
import org.hibernate.annotations.common.reflection.java.JavaXMember;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.usertype.DynamicParameterizedType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

/**
 * Maps a {@link Range} object type to a PostgreSQL <a href="https://www.postgresql.org/docs/current/rangetypes.html">range</a>
 * column type.
 * <p>
 * Supported range types:
 * <ul>
 * <li>int4range</li>
 * <li>int8range</li>
 * <li>numrange</li>
 * <li>tsrange</li>
 * <li>tstzrange</li>
 * <li>daterange</li>
 * </ul>
 *
 * @author Edgar Asatryan
 * @author Vlad Mihalcea
 * @author Jan-Willem Gmelig Meyling
 */
public class PostgreSQLGuavaRangeType extends ImmutableType<Range> implements DynamicParameterizedType {

    public static final PostgreSQLGuavaRangeType INSTANCE = new PostgreSQLGuavaRangeType();

    private Type type;

    public PostgreSQLGuavaRangeType() {
        super(Range.class);
    }

    @Override
    public int[] sqlTypes() {
        return new int[]{Types.OTHER};
    }

    @Override
    protected Range get(ResultSet rs, String[] names, SessionImplementor session, Object owner) throws SQLException {
        Object pgObject = rs.getObject(names[0]);

        if (pgObject == null) {
            return null;
        }

        String type = ReflectionUtils.invokeGetter(pgObject, "type");
        String value = ReflectionUtils.invokeGetter(pgObject, "value");

        if("int4range".equals(type)) {
            return integerRange(value);
        } else if("int8range".equals(type)) {
            return longRange(value);
        } else if("numrange".equals(type)) {
            return bigDecimalRange(value);
        } else {
            throw new HibernateException(
                new IllegalStateException("The range type [" + type + "] is not supported!")
            );
        }
    }

    @Override
    protected void set(PreparedStatement st, Range range, int index, SessionImplementor session) throws SQLException {

        if (range == null) {
            st.setNull(index, Types.OTHER);
        } else {
            Object holder = ReflectionUtils.newInstance("org.postgresql.util.PGobject");
            ReflectionUtils.invokeSetter(holder, "type", determineRangeType(range));
            ReflectionUtils.invokeSetter(holder, "value", asString(range));
            st.setObject(index, holder);
        }
    }

    private static String determineRangeType(Range<?> range) {
        Object anyEndpoint = range.hasLowerBound() ? range.lowerEndpoint() :
                             range.hasUpperBound() ? range.upperEndpoint() : null;

        if (anyEndpoint == null) {
            throw new HibernateException(
                new IllegalArgumentException("The range " + range + " doesn't have any upper or lower bound!")
            );
        }

        Class<?> clazz = anyEndpoint.getClass();

        if (clazz.equals(Integer.class)) {
            return "int4range";
        } else if (clazz.equals(Long.class)) {
            return "int8range";
        } else if (clazz.equals(BigDecimal.class)) {
            return "numrange";
        }

        throw new HibernateException(
            new IllegalStateException("The class [" + clazz.getName() + "] is not supported!")
        );
    }


    @SuppressWarnings("unchecked")
    public static <T extends Comparable> Range<T> ofString(String str, Function<String, T> converter, Class<T> cls) {
        BoundType lowerBound = str.charAt(0) == '[' ? BoundType.CLOSED : BoundType.OPEN;
        BoundType upperBound = str.charAt(str.length() - 1) == ']' ? BoundType.CLOSED : BoundType.OPEN;

        int delim = str.indexOf(',');

        if (delim == -1) {
            throw new HibernateException(
                new IllegalArgumentException("Cannot find comma character")
            );
        }

        String lowerStr = str.substring(1, delim);
        String upperStr = str.substring(delim + 1, str.length() - 1);

        T lower = null;
        T upper = null;

        if (lowerStr.length() > 0) {
            lower = converter.apply(lowerStr);
        }

        if (upperStr.length() > 0) {
            upper = converter.apply(upperStr);
        }

        if (lower == null && upper == null) {
            throw new HibernateException(
                new IllegalArgumentException("Cannot find bound type")
            );
        }

        if (lowerStr.length() == 0) {
            return upperBound == BoundType.CLOSED ?
                Ranges.atMost(upper) :
                Ranges.lessThan(upper);
        } else if (upperStr.length() == 0) {
            return lowerBound == BoundType.CLOSED ?
                Ranges.atLeast(lower) :
                Ranges.greaterThan(lower);
        } else {
            return Ranges.range(lower, lowerBound, upper, upperBound);
        }
    }

    /**
     * Creates the {@code BigDecimal} range from provided string:
     * <pre>{@code
     *     Range<BigDecimal> closed = Range.bigDecimalRange("[0.1,1.1]");
     *     Range<BigDecimal> halfOpen = Range.bigDecimalRange("(0.1,1.1]");
     *     Range<BigDecimal> open = Range.bigDecimalRange("(0.1,1.1)");
     *     Range<BigDecimal> leftUnbounded = Range.bigDecimalRange("(,1.1)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5.5,7.8]"}.
     *
     * @return The range of {@code BigDecimal}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<BigDecimal> bigDecimalRange(String range) {
        return ofString(range, new Function<String, BigDecimal>() {
            @Override
            public BigDecimal apply(String s) {
                return new BigDecimal(s);
            }
        }, BigDecimal.class);
    }

    /**
     * Creates the {@code Integer} range from provided string:
     * <pre>{@code
     *     Range<Integer> closed = Range.integerRange("[1,5]");
     *     Range<Integer> halfOpen = Range.integerRange("(-1,1]");
     *     Range<Integer> open = Range.integerRange("(1,2)");
     *     Range<Integer> leftUnbounded = Range.integerRange("(,10)");
     *     Range<Integer> unbounded = Range.integerRange("(,)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5,7]"}.
     *
     * @return The range of {@code Integer}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<Integer> integerRange(String range) {
        return ofString(range, new Function<String, Integer>() {
            @Override
            public Integer apply(String s) {
                return Integer.parseInt(s);
            }
        }, Integer.class);
    }

    /**
     * Creates the {@code Long} range from provided string:
     * <pre>{@code
     *     Range<Long> closed = Range.longRange("[1,5]");
     *     Range<Long> halfOpen = Range.longRange("(-1,1]");
     *     Range<Long> open = Range.longRange("(1,2)");
     *     Range<Long> leftUnbounded = Range.longRange("(,10)");
     *     Range<Long> unbounded = Range.longRange("(,)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5,7]"}.
     *
     * @return The range of {@code Long}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<Long> longRange(String range) {
        return ofString(range, new Function<String, Long>() {
            @Override
            public Long apply(String s) {
                return Long.parseLong(s);
            }
        }, Long.class);
    }

    public String asString(Range range) {
        StringBuilder sb = new StringBuilder();

        sb.append(range.hasLowerBound() && range.lowerBoundType() == BoundType.CLOSED ? '[' : '(')
                .append(range.hasLowerBound() ? range.lowerEndpoint().toString() : "")
                .append(",")
                .append(range.hasUpperBound() ? range.upperEndpoint().toString() : "")
                .append(range.hasUpperBound() && range.upperBoundType() == BoundType.CLOSED ? ']' : ')');

        return sb.toString();
    }

    @Override
    public void setParameterValues(Properties parameters) {
        final XProperty xProperty = (XProperty) parameters.get(DynamicParameterizedType.XPROPERTY);
        if (xProperty instanceof JavaXMember) {
            type = ReflectionUtils.invokeGetter(xProperty, "javaType");
        } else {
            type = ((ParameterType) parameters.get(PARAMETER_TYPE)).getReturnedClass();
        }
    }

    public Class<?> getElementType() {
        return type instanceof ParameterizedType ?
                (Class<?>) ((ParameterizedType) type).getActualTypeArguments()[0] : null;
    }

    public interface Function<T, R> {

        R apply(T t);
    }
}
