/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.type.descriptor.jdbc;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.HibernateException;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.tool.schema.extract.spi.ColumnTypeInformation;
import org.hibernate.type.BasicType;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.BasicPluralJavaType;
import org.hibernate.type.descriptor.java.BasicJavaType;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.internal.JdbcLiteralFormatterArray;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Descriptor for {@link Types#ARRAY ARRAY} handling.
 *
 * @author Christian Beikov
 * @author Jordan Gigov
 */
public class ArrayJdbcType implements JdbcType {

	public static final ArrayJdbcType INSTANCE = new ArrayJdbcType( ObjectJdbcType.INSTANCE );
	private static final ClassValue<Method> NAME_BINDER = new ClassValue<>() {
		@Override
		protected Method computeValue(Class<?> type) {
			try {
				return type.getMethod( "setArray", String.class, java.sql.Array.class );
			}
			catch ( Exception ex ) {
				// add logging? Did we get NoSuchMethodException or SecurityException?
				// Doesn't matter which. We can't use it.
			}
			return null;
		}
	};

	private final JdbcType elementJdbcType;

	public ArrayJdbcType(JdbcType elementJdbcType) {
		this.elementJdbcType = elementJdbcType;
	}

	public JdbcType resolveType(
			TypeConfiguration typeConfiguration,
			Dialect dialect,
			BasicType<?> elementType,
			ColumnTypeInformation columnTypeInformation) {
		return resolveType( typeConfiguration, dialect, elementType.getJdbcType(), columnTypeInformation );
	}

	public JdbcType resolveType(
			TypeConfiguration typeConfiguration,
			Dialect dialect,
			JdbcType elementType,
			ColumnTypeInformation columnTypeInformation) {
		return new ArrayJdbcType( elementType );
	}

	@Override
	public int getJdbcTypeCode() {
		return Types.ARRAY;
	}

	public JdbcType getElementJdbcType() {
		return elementJdbcType;
	}

	@Override
	public <T> BasicJavaType<T> getJdbcRecommendedJavaTypeMapping(
			Integer precision,
			Integer scale,
			TypeConfiguration typeConfiguration) {
		final BasicJavaType<Object> elementJavaType = elementJdbcType.getJdbcRecommendedJavaTypeMapping(
				precision,
				scale,
				typeConfiguration
		);
		return (BasicJavaType<T>) typeConfiguration.getJavaTypeRegistry().resolveDescriptor(
				Array.newInstance( elementJavaType.getJavaTypeClass(), 0 ).getClass()
		);
	}

	@Override
	public <T> JdbcLiteralFormatter<T> getJdbcLiteralFormatter(JavaType<T> javaTypeDescriptor) {
		//noinspection unchecked
		final BasicPluralJavaType<T> basicPluralJavaType = (BasicPluralJavaType<T>) javaTypeDescriptor;
		final JdbcLiteralFormatter<T> elementFormatter = elementJdbcType.getJdbcLiteralFormatter( basicPluralJavaType.getElementJavaType() );
		return new JdbcLiteralFormatterArray<>( javaTypeDescriptor, elementFormatter );
	}

	@Override
	public Class<?> getPreferredJavaTypeClass(WrapperOptions options) {
		return java.sql.Array.class;
	}

	@Override
	public <X> ValueBinder<X> getBinder(final JavaType<X> javaTypeDescriptor) {
		//noinspection unchecked
		final BasicPluralJavaType<X> containerJavaType = (BasicPluralJavaType<X>) javaTypeDescriptor;
		return new BasicBinder<X>( javaTypeDescriptor, this ) {

			@Override
			protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) throws SQLException {
				final java.sql.Array arr = getArray( value, containerJavaType, options );
				st.setArray( index, arr );
			}

			@Override
			protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
					throws SQLException {
				final java.sql.Array arr = getArray( value, containerJavaType, options );
				final Method nameBinder = NAME_BINDER.get( st.getClass() );
				if ( nameBinder == null ) {
					try {
						st.setObject( name, arr, java.sql.Types.ARRAY );
						return;
					}
					catch (SQLException ex) {
						throw new HibernateException( "JDBC driver does not support named parameters for setArray. Use positional.", ex );
					}
				}
				// Note that it's supposed to have setArray(String,Array) by standard.
				// There are numerous missing methods that only have versions for positional parameter,
				// but not named ones.

				try {
					nameBinder.invoke( st, name, arr );
				}
				catch ( Throwable t ) {
					throw new HibernateException( t );
				}
			}

			private java.sql.Array getArray(
					X value,
					BasicPluralJavaType<X> containerJavaType,
					WrapperOptions options) throws SQLException {
				final TypeConfiguration typeConfiguration = options.getSessionFactory().getTypeConfiguration();
				final JdbcType underlyingJdbcType = typeConfiguration.getJdbcTypeRegistry()
						.getDescriptor( elementJdbcType.getDefaultSqlTypeCode() );
				final Class<?> preferredJavaTypeClass = elementJdbcType.getPreferredJavaTypeClass( options );
				final Class<?> elementJdbcJavaTypeClass;
				if ( preferredJavaTypeClass == null ) {
					elementJdbcJavaTypeClass = underlyingJdbcType.getJdbcRecommendedJavaTypeMapping(
							null,
							null,
							typeConfiguration
					).getJavaTypeClass();
				}
				else {
					elementJdbcJavaTypeClass = preferredJavaTypeClass;
				}
				//noinspection unchecked
				final Class<Object[]> arrayClass = (Class<Object[]>) Array.newInstance(
						elementJdbcJavaTypeClass,
						0
				).getClass();
				final Object[] objects = javaTypeDescriptor.unwrap( value, arrayClass, options );

				final SharedSessionContractImplementor session = options.getSession();
				// TODO: ideally, we would have the actual size or the actual type/column accessible
				//  this is something that we would need for supporting composite types anyway
				final Size size = session.getJdbcServices()
						.getDialect()
						.getSizeStrategy()
						.resolveSize( elementJdbcType, containerJavaType.getElementJavaType(), null, null, null );
				String typeName = session.getTypeConfiguration()
						.getDdlTypeRegistry()
						.getDescriptor( elementJdbcType.getDefaultSqlTypeCode() )
						.getTypeName( size );
				int cutIndex = typeName.indexOf( '(' );
				if ( cutIndex > 0 ) {
					// getTypeName for this case required length, etc, parameters.
					// Cut them out and use database defaults.
					typeName = typeName.substring( 0, cutIndex );
				}
				return session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection().createArrayOf( typeName, objects );
			}
		};
	}

	@Override
	public <X> ValueExtractor<X> getExtractor(final JavaType<X> javaTypeDescriptor) {
		return new BasicExtractor<X>( javaTypeDescriptor, this ) {
			@Override
			protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
				return javaTypeDescriptor.wrap( rs.getArray( paramIndex ), options );
			}

			@Override
			protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
				return javaTypeDescriptor.wrap( statement.getArray( index ), options );
			}

			@Override
			protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
				return javaTypeDescriptor.wrap( statement.getArray( name ), options );
			}
		};
	}

	@Override
	public String getFriendlyName() {
		return "ARRAY";
	}

	@Override
	public String toString() {
		return "ArrayTypeDescriptor(" + getFriendlyName() + ")";
	}

	@Override
	public boolean equals(Object o) {
		if ( this == o ) {
			return true;
		}
		if ( o == null || getClass() != o.getClass() ) {
			return false;
		}

		ArrayJdbcType that = (ArrayJdbcType) o;

		return elementJdbcType.equals( that.elementJdbcType );
	}

	@Override
	public int hashCode() {
		return elementJdbcType.hashCode();
	}
}