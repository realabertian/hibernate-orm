/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.mapping.generated.temporals;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.HibernateError;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.tuple.GenerationTiming;

import org.hibernate.testing.orm.junit.DialectFeatureChecks;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.RequiresDialectFeature;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Steve Ebersole
 */
@DomainModel( annotatedClasses = GeneratedInstantTests.GeneratedInstantEntity.class )
@SessionFactory
@RequiresDialectFeature(feature = DialectFeatureChecks.UsesStandardCurrentTimestampFunction.class, comment = "We rely on current_timestamp being the SQL function name")
@RequiresDialectFeature(feature = DialectFeatureChecks.CurrentTimestampHasMicrosecondPrecision.class, comment = "Without this, we might not see an update to the timestamp")
public class GeneratedInstantTests {
	@Test
	public void test(SessionFactoryScope scope) {
		final GeneratedInstantEntity created = scope.fromTransaction( (session) -> {
			final GeneratedInstantEntity entity = new GeneratedInstantEntity( 1, "tsifr" );
			session.persist( entity );
			return entity;
		} );

		assertThat( created.createdAt ).isNotNull();
		assertThat( created.updatedAt ).isNotNull();
		assertThat( created.createdAt ).isEqualTo( created.updatedAt );

//		assertThat( created.createdAt2 ).isNotNull();
//		assertThat( created.updatedAt2 ).isNotNull();
//		assertThat( created.createdAt2 ).isEqualTo( created.updatedAt2 );

		created.name = "first";

		//We need to wait a little to make sure the timestamps produced are different
		waitALittle();

		// then changing
		final GeneratedInstantEntity merged = scope.fromTransaction( (session) -> {
			return (GeneratedInstantEntity) session.merge( created );
		} );

		assertThat( merged ).isNotNull();
		assertThat( merged.createdAt ).isNotNull();
		assertThat( merged.updatedAt ).isNotNull();
		assertThat( merged.createdAt ).isEqualTo( created.createdAt );
		assertThat( merged.updatedAt ).isNotEqualTo( created.updatedAt );

		assertThat( merged ).isNotNull();
//		assertThat( merged.createdAt2 ).isNotNull();
//		assertThat( merged.updatedAt2 ).isNotNull();
//		assertThat( merged.createdAt2 ).isEqualTo( created.createdAt2 );
//		assertThat( merged.updatedAt2 ).isNotEqualTo( created.updatedAt2 );

		//We need to wait a little to make sure the timestamps produced are different
		waitALittle();

		// lastly, make sure we can load it..
		final GeneratedInstantEntity loaded = scope.fromTransaction( (session) -> {
			return session.get( GeneratedInstantEntity.class, 1 );
		} );

		assertThat( loaded ).isNotNull();

		assertThat( loaded.createdAt ).isEqualTo( merged.createdAt );
		assertThat( loaded.updatedAt ).isEqualTo( merged.updatedAt );

//		assertThat( loaded.createdAt2 ).isEqualTo( merged.createdAt2 );
//		assertThat( loaded.updatedAt2 ).isEqualTo( merged.updatedAt2 );
	}

	@Entity( name = "GeneratedInstantEntity" )
	@Table( name = "gen_ann_instant" )
	public static class GeneratedInstantEntity {
		@Id
		public Integer id;
		public String name;

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// Legacy `Generated`

		@CurrentTimestamp( timing = GenerationTiming.INSERT )
		public Instant createdAt;

		@CurrentTimestamp( timing = GenerationTiming.ALWAYS )
		public Instant updatedAt;

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// `GeneratedValue`

//		@ProposedGenerated( timing = GenerationTiming.INSERT, sqlDefaultValue = "current_timestamp" )
//		public Instant createdAt2;
//		@ProposedGenerated( timing = GenerationTiming.ALWAYS, sqlDefaultValue = "current_timestamp" )
//		public Instant updatedAt2;

		public GeneratedInstantEntity() {
		}

		public GeneratedInstantEntity(Integer id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private static void waitALittle() {
		try {
			Thread.sleep( 10 );
		}
		catch (InterruptedException e) {
			throw new HibernateError( "Unexpected wakeup from test sleep" );
		}
	}
}
