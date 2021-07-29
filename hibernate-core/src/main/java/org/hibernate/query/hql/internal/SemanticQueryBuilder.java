/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.hql.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.QueryException;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.boot.registry.classloading.spi.ClassLoadingException;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
import org.hibernate.grammars.hql.HqlParserBaseVisitor;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.internal.util.collections.StandardStack;
import org.hibernate.metamodel.CollectionClassification;
import org.hibernate.metamodel.model.domain.AllowableFunctionReturnType;
import org.hibernate.metamodel.model.domain.BasicDomainType;
import org.hibernate.metamodel.model.domain.DomainType;
import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.metamodel.model.domain.IdentifiableDomainType;
import org.hibernate.metamodel.model.domain.ManagedDomainType;
import org.hibernate.metamodel.model.domain.PluralPersistentAttribute;
import org.hibernate.metamodel.model.domain.SingularPersistentAttribute;
import org.hibernate.query.BinaryArithmeticOperator;
import org.hibernate.query.ComparisonOperator;
import org.hibernate.query.FetchClauseType;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.PathException;
import org.hibernate.query.SemanticException;
import org.hibernate.query.SetOperator;
import org.hibernate.query.SortOrder;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.TrimSpec;
import org.hibernate.query.UnaryArithmeticOperator;
import org.hibernate.query.hql.HqlLogging;
import org.hibernate.query.hql.spi.DotIdentifierConsumer;
import org.hibernate.query.hql.spi.SemanticPathPart;
import org.hibernate.query.hql.spi.SqmCreationOptions;
import org.hibernate.query.hql.spi.SqmCreationProcessingState;
import org.hibernate.query.hql.spi.SqmCreationState;
import org.hibernate.query.hql.spi.SqmPathRegistry;
import org.hibernate.query.sqm.LiteralNumberFormatException;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.ParsingException;
import org.hibernate.query.sqm.SqmExpressable;
import org.hibernate.query.sqm.SqmPathSource;
import org.hibernate.query.sqm.SqmQuerySource;
import org.hibernate.query.sqm.SqmTreeCreationLogger;
import org.hibernate.query.sqm.StrictJpaComplianceViolation;
import org.hibernate.query.sqm.UnknownEntityException;
import org.hibernate.query.sqm.function.NamedSqmFunctionDescriptor;
import org.hibernate.query.sqm.function.SqmFunctionDescriptor;
import org.hibernate.query.sqm.internal.ParameterCollector;
import org.hibernate.query.sqm.internal.SqmCreationProcessingStateImpl;
import org.hibernate.query.sqm.internal.SqmDmlCreationProcessingState;
import org.hibernate.query.sqm.internal.SqmQuerySpecCreationProcessingStateStandardImpl;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.query.sqm.spi.ParameterDeclarationContext;
import org.hibernate.query.sqm.spi.SqmCreationContext;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.SqmQuery;
import org.hibernate.query.sqm.tree.SqmStatement;
import org.hibernate.query.sqm.tree.SqmTypedNode;
import org.hibernate.query.sqm.tree.delete.SqmDeleteStatement;
import org.hibernate.query.sqm.tree.domain.AbstractSqmFrom;
import org.hibernate.query.sqm.tree.domain.SqmCorrelation;
import org.hibernate.query.sqm.tree.domain.SqmIndexedCollectionAccessPath;
import org.hibernate.query.sqm.tree.domain.SqmListJoin;
import org.hibernate.query.sqm.tree.domain.SqmMapEntryReference;
import org.hibernate.query.sqm.tree.domain.SqmMapJoin;
import org.hibernate.query.sqm.tree.domain.SqmMaxElementPath;
import org.hibernate.query.sqm.tree.domain.SqmMaxIndexPath;
import org.hibernate.query.sqm.tree.domain.SqmMinElementPath;
import org.hibernate.query.sqm.tree.domain.SqmMinIndexPath;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.domain.SqmPluralValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmPolymorphicRootDescriptor;
import org.hibernate.query.sqm.tree.domain.SqmTreatedPath;
import org.hibernate.query.sqm.tree.expression.SqmAliasedNodeRef;
import org.hibernate.query.sqm.tree.expression.SqmAny;
import org.hibernate.query.sqm.tree.expression.SqmBinaryArithmetic;
import org.hibernate.query.sqm.tree.expression.SqmByUnit;
import org.hibernate.query.sqm.tree.expression.SqmCaseSearched;
import org.hibernate.query.sqm.tree.expression.SqmCaseSimple;
import org.hibernate.query.sqm.tree.expression.SqmCastTarget;
import org.hibernate.query.sqm.tree.expression.SqmCollate;
import org.hibernate.query.sqm.tree.expression.SqmCollectionSize;
import org.hibernate.query.sqm.tree.expression.SqmDistinct;
import org.hibernate.query.sqm.tree.expression.SqmDurationUnit;
import org.hibernate.query.sqm.tree.expression.SqmEvery;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmExtractUnit;
import org.hibernate.query.sqm.tree.expression.SqmFormat;
import org.hibernate.query.sqm.tree.expression.SqmLiteral;
import org.hibernate.query.sqm.tree.expression.SqmLiteralNull;
import org.hibernate.query.sqm.tree.expression.SqmNamedParameter;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.query.sqm.tree.expression.SqmParameterizedEntityType;
import org.hibernate.query.sqm.tree.expression.SqmPositionalParameter;
import org.hibernate.query.sqm.tree.expression.SqmStar;
import org.hibernate.query.sqm.tree.expression.SqmSummarization;
import org.hibernate.query.sqm.tree.expression.SqmToDuration;
import org.hibernate.query.sqm.tree.expression.SqmTrimSpecification;
import org.hibernate.query.sqm.tree.expression.SqmTuple;
import org.hibernate.query.sqm.tree.expression.SqmUnaryOperation;
import org.hibernate.query.sqm.tree.from.DowncastLocation;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmCrossJoin;
import org.hibernate.query.sqm.tree.from.SqmEntityJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.from.SqmQualifiedJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.insert.SqmInsertSelectStatement;
import org.hibernate.query.sqm.tree.insert.SqmInsertStatement;
import org.hibernate.query.sqm.tree.insert.SqmInsertValuesStatement;
import org.hibernate.query.sqm.tree.insert.SqmValues;
import org.hibernate.query.sqm.tree.predicate.SqmAndPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmBetweenPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmBooleanExpressionPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmEmptinessPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmExistsPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmGroupedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInListPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInSubQueryPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmLikePredicate;
import org.hibernate.query.sqm.tree.predicate.SqmMemberOfPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmNegatablePredicate;
import org.hibernate.query.sqm.tree.predicate.SqmNegatedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmNullnessPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmOrPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmWhereClause;
import org.hibernate.query.sqm.tree.select.AbstractSqmSelectQuery;
import org.hibernate.query.sqm.tree.select.SqmAliasedNode;
import org.hibernate.query.sqm.tree.select.SqmDynamicInstantiation;
import org.hibernate.query.sqm.tree.select.SqmDynamicInstantiationArgument;
import org.hibernate.query.sqm.tree.select.SqmOrderByClause;
import org.hibernate.query.sqm.tree.select.SqmQueryGroup;
import org.hibernate.query.sqm.tree.select.SqmQueryPart;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectClause;
import org.hibernate.query.sqm.tree.select.SqmSelectQuery;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.query.sqm.tree.select.SqmSelectableNode;
import org.hibernate.query.sqm.tree.select.SqmSelection;
import org.hibernate.query.sqm.tree.select.SqmSortSpecification;
import org.hibernate.query.sqm.tree.select.SqmSubQuery;
import org.hibernate.query.sqm.tree.update.SqmUpdateStatement;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;

import org.jboss.logging.Logger;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hibernate.grammars.hql.HqlParser.EXCEPT;
import static org.hibernate.grammars.hql.HqlParser.IDENTIFIER;
import static org.hibernate.grammars.hql.HqlParser.INTERSECT;
import static org.hibernate.grammars.hql.HqlParser.PLUS;
import static org.hibernate.grammars.hql.HqlParser.UNION;
import static org.hibernate.query.TemporalUnit.DATE;
import static org.hibernate.query.TemporalUnit.DAY_OF_MONTH;
import static org.hibernate.query.TemporalUnit.DAY_OF_WEEK;
import static org.hibernate.query.TemporalUnit.DAY_OF_YEAR;
import static org.hibernate.query.TemporalUnit.NANOSECOND;
import static org.hibernate.query.TemporalUnit.OFFSET;
import static org.hibernate.query.TemporalUnit.TIME;
import static org.hibernate.query.TemporalUnit.TIMEZONE_HOUR;
import static org.hibernate.query.TemporalUnit.TIMEZONE_MINUTE;
import static org.hibernate.query.TemporalUnit.WEEK_OF_MONTH;
import static org.hibernate.query.TemporalUnit.WEEK_OF_YEAR;
import static org.hibernate.type.descriptor.DateTimeUtils.DATE_TIME;
import static org.hibernate.type.spi.TypeConfiguration.isJdbcTemporalType;

/**
 * Responsible for producing an SQM using visitation over an HQL parse tree generated by
 * Antlr via {@link HqlParseTreeBuilder}.
 *
 * @author Steve Ebersole
 */
public class SemanticQueryBuilder<R> extends HqlParserBaseVisitor<Object> implements SqmCreationState {

	private static final Logger log = Logger.getLogger( SemanticQueryBuilder.class );

	/**
	 * Main entry point into analysis of HQL/JPQL parse tree - producing a semantic model of the
	 * query.
	 */
	@SuppressWarnings("WeakerAccess")
	public static <R> SqmStatement<R> buildSemanticModel(
			HqlParser.StatementContext hqlParseTree,
			SqmCreationOptions creationOptions,
			SqmCreationContext creationContext) {
		return new SemanticQueryBuilder<R>( creationOptions, creationContext ).visitStatement( hqlParseTree );
	}

	private final SqmCreationOptions creationOptions;
	private final SqmCreationContext creationContext;

	private final Stack<DotIdentifierConsumer> dotIdentifierConsumerStack;

	private final Stack<TreatHandler> treatHandlerStack = new StandardStack<>( new TreatHandlerNormal() );

	private final Stack<ParameterDeclarationContext> parameterDeclarationContextStack = new StandardStack<>();
	private final Stack<SqmCreationProcessingState> processingStateStack = new StandardStack<>();

	private final BasicDomainType<Integer> integerDomainType;
	private final JavaTypeDescriptor<List<?>> listJavaTypeDescriptor;
	private final JavaTypeDescriptor<Map<?,?>> mapJavaTypeDescriptor;

	private ParameterCollector parameterCollector;

	@SuppressWarnings("WeakerAccess")
	public SemanticQueryBuilder(SqmCreationOptions creationOptions, SqmCreationContext creationContext) {
		this.creationOptions = creationOptions;
		this.creationContext = creationContext;
		this.dotIdentifierConsumerStack = new StandardStack<>( new BasicDotIdentifierConsumer( this ) );

		this.integerDomainType = creationContext
				.getNodeBuilder()
				.getTypeConfiguration()
				.standardBasicTypeForJavaType( Integer.class );
		this.listJavaTypeDescriptor = creationContext
				.getNodeBuilder()
				.getTypeConfiguration()
				.getJavaTypeDescriptorRegistry()
				.resolveDescriptor( List.class );
		this.mapJavaTypeDescriptor = creationContext
				.getNodeBuilder()
				.getTypeConfiguration()
				.getJavaTypeDescriptorRegistry()
				.resolveDescriptor( Map.class );
	}

	@Override
	public SqmCreationContext getCreationContext() {
		return creationContext;
	}

	@Override
	public SqmCreationOptions getCreationOptions() {
		return creationOptions;
	}

	public Stack<SqmCreationProcessingState> getProcessingStateStack() {
		return processingStateStack;
	}

	protected Stack<ParameterDeclarationContext> getParameterDeclarationContextStack() {
		return parameterDeclarationContextStack;
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Grammar rules

	@Override
	public SqmStatement<R> visitStatement(HqlParser.StatementContext ctx) {
		// parameters allow multi-valued bindings only in very limited cases, so for
		// the base case here we say false
		parameterDeclarationContextStack.push( () -> false );

		try {
			final ParseTree parseTree = ctx.getChild( 0 );
			if ( parseTree instanceof HqlParser.SelectStatementContext ) {
				final SqmSelectStatement<R> selectStatement = visitSelectStatement( (HqlParser.SelectStatementContext) parseTree );
				selectStatement.getQueryPart().validateQueryGroupFetchStructure();
				return selectStatement;
			}
			else if ( parseTree instanceof HqlParser.InsertStatementContext ) {
				return visitInsertStatement( (HqlParser.InsertStatementContext) parseTree );
			}
			else if ( parseTree instanceof HqlParser.UpdateStatementContext ) {
				return visitUpdateStatement( (HqlParser.UpdateStatementContext) parseTree );
			}
			else if ( parseTree instanceof HqlParser.DeleteStatementContext ) {
				return visitDeleteStatement( (HqlParser.DeleteStatementContext) parseTree );
			}
		}
		finally {
			parameterDeclarationContextStack.pop();
		}

		throw new ParsingException( "Unexpected statement type [not INSERT, UPDATE, DELETE or SELECT] : " + ctx.getText() );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Top-level statements

	@Override
	public SqmSelectStatement<R> visitSelectStatement(HqlParser.SelectStatementContext ctx) {
		final HqlParser.QueryExpressionContext queryExpressionContext = ctx.queryExpression();
		final SqmSelectStatement<R> selectStatement = new SqmSelectStatement<>( creationContext.getNodeBuilder() );

		parameterCollector = selectStatement;

		processingStateStack.push(
				new SqmQuerySpecCreationProcessingStateStandardImpl(
						processingStateStack.getCurrent(),
						selectStatement,
						this
				)
		);

		try {
			queryExpressionContext.accept( this );
		}
		finally {
			processingStateStack.pop();
		}

		return selectStatement;
	}

	@Override
	public SqmRoot<R> visitDmlTarget(HqlParser.DmlTargetContext dmlTargetContext) {
		final HqlParser.EntityNameContext entityNameContext = (HqlParser.EntityNameContext) dmlTargetContext.getChild( 0 );
		final String identificationVariable;
		if ( dmlTargetContext.getChildCount() == 1 ) {
			identificationVariable = null;
		}
		else {
			identificationVariable = applyJpaCompliance(
					visitIdentificationVariableDef(
							(HqlParser.IdentificationVariableDefContext) dmlTargetContext.getChild( 1 )
					)
			);
		}
		//noinspection unchecked
		return new SqmRoot<>(
				(EntityDomainType<R>) visitEntityName( entityNameContext ),
				identificationVariable,
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmInsertStatement<R> visitInsertStatement(HqlParser.InsertStatementContext ctx) {
		final int dmlTargetIndex;
		if ( ctx.getChild( 1 ) instanceof HqlParser.DmlTargetContext ) {
			dmlTargetIndex = 1;
		}
		else {
			dmlTargetIndex = 2;
		}
		final HqlParser.DmlTargetContext dmlTargetContext = (HqlParser.DmlTargetContext) ctx.getChild( dmlTargetIndex );
		final HqlParser.TargetFieldsSpecContext targetFieldsSpecContext = (HqlParser.TargetFieldsSpecContext) ctx.getChild(
				dmlTargetIndex + 1
		);
		final SqmRoot<R> root = visitDmlTarget( dmlTargetContext );

		final HqlParser.QueryExpressionContext queryExpressionContext = ctx.queryExpression();
		if ( queryExpressionContext != null ) {
			final SqmInsertSelectStatement<R> insertStatement = new SqmInsertSelectStatement<>( root, creationContext.getNodeBuilder() );
			parameterCollector = insertStatement;
			final SqmDmlCreationProcessingState processingState = new SqmDmlCreationProcessingState(
					insertStatement,
					this
			);

			processingStateStack.push( processingState );

			try {
				queryExpressionContext.accept( this );

				final SqmCreationProcessingState stateFieldsProcessingState = new SqmCreationProcessingStateImpl(
						insertStatement,
						this
				);
				stateFieldsProcessingState.getPathRegistry().register( root );

				processingStateStack.push( stateFieldsProcessingState );
				try {
					for ( HqlParser.DotIdentifierSequenceContext stateFieldCtx : targetFieldsSpecContext.dotIdentifierSequence() ) {
						final SqmPath<?> stateField = (SqmPath<?>) visitDotIdentifierSequence( stateFieldCtx );
						// todo : validate each resolved stateField...
						insertStatement.addInsertTargetStateField( stateField );
					}
				}
				finally {
					processingStateStack.pop();
				}

				return insertStatement;
			}
			finally {
				processingStateStack.pop();
			}

		}
		else {
			final SqmInsertValuesStatement<R> insertStatement = new SqmInsertValuesStatement<>( root, creationContext.getNodeBuilder() );
			parameterCollector = insertStatement;
			final SqmDmlCreationProcessingState processingState = new SqmDmlCreationProcessingState(
					insertStatement,
					this
			);

			processingStateStack.push( processingState );
			processingState.getPathRegistry().register( root );

			try {
				for ( HqlParser.ValuesContext values : ctx.valuesList().values() ) {
					SqmValues sqmValues = new SqmValues();
					for ( HqlParser.ExpressionContext expressionContext : values.expression() ) {
						sqmValues.getExpressions().add( (SqmExpression<?>) expressionContext.accept( this ) );
					}
					insertStatement.getValuesList().add( sqmValues );
				}

				for ( HqlParser.DotIdentifierSequenceContext stateFieldCtx : targetFieldsSpecContext.dotIdentifierSequence() ) {
					final SqmPath<?> stateField = (SqmPath<?>) visitDotIdentifierSequence( stateFieldCtx );
					// todo : validate each resolved stateField...
					insertStatement.addInsertTargetStateField( stateField );
				}

				return insertStatement;
			}
			finally {
				processingStateStack.pop();
			}

		}
	}

	@Override
	public SqmUpdateStatement<R> visitUpdateStatement(HqlParser.UpdateStatementContext ctx) {
		final boolean versioned = !( ctx.getChild( 1 ) instanceof HqlParser.DmlTargetContext );
		final int dmlTargetIndex = versioned ? 2 : 1;
		final HqlParser.DmlTargetContext dmlTargetContext = (HqlParser.DmlTargetContext) ctx.getChild( dmlTargetIndex );
		final SqmRoot<R> root = visitDmlTarget( dmlTargetContext );

		final SqmUpdateStatement<R> updateStatement = new SqmUpdateStatement<>( root, creationContext.getNodeBuilder() );
		parameterCollector = updateStatement;
		final SqmDmlCreationProcessingState processingState = new SqmDmlCreationProcessingState(
				updateStatement,
				this
		);
		processingStateStack.push( processingState );
		processingState.getPathRegistry().register( root );

		try {
			updateStatement.versioned( versioned );
			final HqlParser.SetClauseContext setClauseCtx = (HqlParser.SetClauseContext) ctx.getChild( dmlTargetIndex + 1 );
			for ( ParseTree subCtx : setClauseCtx.children ) {
				if ( subCtx instanceof HqlParser.AssignmentContext ) {
					final HqlParser.AssignmentContext assignmentContext = (HqlParser.AssignmentContext) subCtx;
					updateStatement.applyAssignment(
							consumeDomainPath( (HqlParser.DotIdentifierSequenceContext) assignmentContext.getChild( 0 ) ),
							(SqmExpression<?>) assignmentContext.getChild( 2 ).accept( this )
					);
				}
			}

			if ( dmlTargetIndex + 2 <= ctx.getChildCount() ) {
				updateStatement.applyPredicate(
						visitWhereClause( (HqlParser.WhereClauseContext) ctx.getChild( dmlTargetIndex + 2 ) )
				);
			}

			return updateStatement;
		}
		finally {
			processingStateStack.pop();
		}
	}

	@Override
	public SqmDeleteStatement<R> visitDeleteStatement(HqlParser.DeleteStatementContext ctx) {
		final int dmlTargetIndex;
		if ( ctx.getChild( 1 ) instanceof HqlParser.DmlTargetContext ) {
			dmlTargetIndex = 1;
		}
		else {
			dmlTargetIndex = 2;
		}
		final HqlParser.DmlTargetContext dmlTargetContext = (HqlParser.DmlTargetContext) ctx.getChild( dmlTargetIndex );
		final SqmRoot<R> root = visitDmlTarget( dmlTargetContext );

		final SqmDeleteStatement<R> deleteStatement = new SqmDeleteStatement<>( root, SqmQuerySource.HQL, creationContext.getNodeBuilder() );

		parameterCollector = deleteStatement;

		final SqmDmlCreationProcessingState sqmDeleteCreationState = new SqmDmlCreationProcessingState(
				deleteStatement,
				this
		);

		sqmDeleteCreationState.getPathRegistry().register( root );

		processingStateStack.push( sqmDeleteCreationState );
		try {
			if ( dmlTargetIndex + 1 <= ctx.getChildCount() ) {
				deleteStatement.applyPredicate(
						visitWhereClause( (HqlParser.WhereClauseContext) ctx.getChild( dmlTargetIndex + 1 ) )
				);
			}

			return deleteStatement;
		}
		finally {
			processingStateStack.pop();
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Query spec

	@Override
	public SqmQueryPart<Object> visitSimpleQueryGroup(HqlParser.SimpleQueryGroupContext ctx) {
		//noinspection unchecked
		return (SqmQueryPart<Object>) ctx.getChild( 0 ).accept( this );
	}

	@Override
	public SqmQueryPart<Object> visitQuerySpecExpression(HqlParser.QuerySpecExpressionContext ctx) {
		final List<ParseTree> children = ctx.children;
		final SqmQueryPart<Object> queryPart = visitQuerySpec( (HqlParser.QuerySpecContext) children.get( 0 ) );
		if ( children.size() > 1 ) {
			visitQueryOrder( queryPart, (HqlParser.QueryOrderContext) children.get( 1 ) );
		}
		return queryPart;
	}

	@Override
	public SqmQueryPart<Object> visitNestedQueryExpression(HqlParser.NestedQueryExpressionContext ctx) {
		final List<ParseTree> children = ctx.children;
		//noinspection unchecked
		final SqmQueryPart<Object> queryPart = (SqmQueryPart<Object>) children.get( 1 ).accept( this );
		if ( children.size() > 3 ) {
			visitQueryOrder( queryPart, (HqlParser.QueryOrderContext) children.get( 3 ) );
		}
		return queryPart;
	}

	@Override
	public SqmQueryGroup<Object> visitSetQueryGroup(HqlParser.SetQueryGroupContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation(
					StrictJpaComplianceViolation.Type.SET_OPERATIONS
			);
		}
		final List<ParseTree> children = ctx.children;
		//noinspection unchecked
		final SqmQueryPart<Object> firstQueryPart = (SqmQueryPart<Object>) children.get( 0 ).accept( this );
		SqmQueryGroup<Object> queryGroup;
		if ( firstQueryPart instanceof SqmQueryGroup<?>) {
			queryGroup = (SqmQueryGroup<Object>) firstQueryPart;
		}
		else {
			queryGroup = new SqmQueryGroup<>( firstQueryPart );
		}
		setCurrentQueryPart( queryGroup );
		final int size = children.size();
		final SqmCreationProcessingState firstProcessingState = processingStateStack.pop();
		for ( int i = 1; i < size; i += 2 ) {
			final SetOperator operator = visitSetOperator( (HqlParser.SetOperatorContext) children.get( i ) );
			final HqlParser.SimpleQueryExpressionContext simpleQueryCtx =
					(HqlParser.SimpleQueryExpressionContext) children.get( i + 1 );
			final List<SqmQueryPart<Object>> queryParts;
			if ( queryGroup.getSetOperator() == null || queryGroup.getSetOperator() == operator ) {
				queryGroup.setSetOperator( operator );
				queryParts = queryGroup.queryParts();
			}
			else {
				queryParts = new ArrayList<>( size - ( i >> 1 ) );
				queryParts.add( queryGroup );
				queryGroup = new SqmQueryGroup<>(
						creationContext.getNodeBuilder(),
						operator,
						queryParts
				);
				setCurrentQueryPart( queryGroup );
			}

			final SqmQueryPart<Object> queryPart;
			try {
				processingStateStack.push(
						new SqmQuerySpecCreationProcessingStateStandardImpl(
								processingStateStack.getCurrent(),
								(SqmSelectQuery<?>) firstProcessingState.getProcessingQuery(),
								this
						)
				);
				final List<ParseTree> subChildren = simpleQueryCtx.children;
				if ( subChildren.get( 0 ) instanceof HqlParser.QuerySpecContext ) {
					final SqmQuerySpec<Object> querySpec = new SqmQuerySpec<>( creationContext.getNodeBuilder() );
					queryParts.add( querySpec );
					visitQuerySpecExpression( (HqlParser.QuerySpecExpressionContext) simpleQueryCtx );
				}
				else {
					try {
						final SqmSelectStatement<Object> selectStatement = new SqmSelectStatement<>( creationContext.getNodeBuilder() );
						processingStateStack.push(
								new SqmQuerySpecCreationProcessingStateStandardImpl(
										processingStateStack.getCurrent(),
										selectStatement,
										this
								)
						);
						queryPart = visitNestedQueryExpression( (HqlParser.NestedQueryExpressionContext) simpleQueryCtx );
						queryParts.add( queryPart );
					}
					finally {
						processingStateStack.pop();
					}
				}
			}
			finally {
				processingStateStack.pop();
			}
		}
		processingStateStack.push( firstProcessingState );

		return queryGroup;
	}

	@Override
	public SetOperator visitSetOperator(HqlParser.SetOperatorContext ctx) {
		final Token token = ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol();
		final boolean all = ctx.getChildCount() == 2;
		switch ( token.getType() ) {
			case UNION:
				return all ? SetOperator.UNION_ALL : SetOperator.UNION;
			case INTERSECT:
				return all ? SetOperator.INTERSECT_ALL : SetOperator.INTERSECT;
			case EXCEPT:
				return all ? SetOperator.EXCEPT_ALL : SetOperator.EXCEPT;
		}
		throw new SemanticException( "Illegal set operator token: " + token.getText() );
	}

	protected void visitQueryOrder(SqmQueryPart<?> sqmQueryPart, HqlParser.QueryOrderContext ctx) {
		if ( ctx == null ) {
			return;
		}
		final SqmOrderByClause orderByClause;
		final HqlParser.OrderByClauseContext orderByClauseContext = (HqlParser.OrderByClauseContext) ctx.getChild( 0 );
		if ( orderByClauseContext != null ) {
			if ( creationOptions.useStrictJpaCompliance() && processingStateStack.depth() > 1 ) {
				throw new StrictJpaComplianceViolation(
						StrictJpaComplianceViolation.Type.SUBQUERY_ORDER_BY
				);
			}

			orderByClause = visitOrderByClause( orderByClauseContext );
			sqmQueryPart.setOrderByClause( orderByClause );
		}
		else {
			orderByClause = null;
		}

		int currentIndex = 1;
		final HqlParser.LimitClauseContext limitClauseContext;
		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.LimitClauseContext ) {
			limitClauseContext = (HqlParser.LimitClauseContext) ctx.getChild( currentIndex++ );
		}
		else {
			limitClauseContext = null;
		}
		final HqlParser.OffsetClauseContext offsetClauseContext;
		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.OffsetClauseContext ) {
			offsetClauseContext = (HqlParser.OffsetClauseContext) ctx.getChild( currentIndex++ );
		}
		else {
			offsetClauseContext = null;
		}
		final HqlParser.FetchClauseContext fetchClauseContext;
		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.FetchClauseContext ) {
			fetchClauseContext = (HqlParser.FetchClauseContext) ctx.getChild( currentIndex++ );
		}
		else {
			fetchClauseContext = null;
		}
		if ( currentIndex != 1 ) {
			if ( getCreationOptions().useStrictJpaCompliance() ) {
				throw new StrictJpaComplianceViolation(
						StrictJpaComplianceViolation.Type.LIMIT_OFFSET_CLAUSE
				);
			}

			if ( processingStateStack.depth() > 1 && orderByClause == null ) {
				throw new SemanticException(
						"limit, offset and fetch clause require an order-by clause when used in sub-query"
				);
			}

			sqmQueryPart.setOffsetExpression( visitOffsetClause( offsetClauseContext ) );
			if ( limitClauseContext == null ) {
				sqmQueryPart.setFetchExpression( visitFetchClause( fetchClauseContext ), visitFetchClauseType( fetchClauseContext ) );
			}
			else if ( fetchClauseContext == null ) {
				sqmQueryPart.setFetchExpression( visitLimitClause( limitClauseContext ) );
			}
			else {
				throw new SemanticException("Can't use both, limit and fetch clause!" );
			}
		}
	}

	@Override
	public SqmQuerySpec<Object> visitQuerySpec(HqlParser.QuerySpecContext ctx) {
		//noinspection unchecked
		final SqmQuerySpec<Object> sqmQuerySpec = (SqmQuerySpec<Object>) currentQuerySpec();
		final int fromIndex;
		if ( ctx.getChild( 0 ) instanceof HqlParser.FromClauseContext ) {
			fromIndex = 0;
		}
		else {
			fromIndex = 1;
		}

		// visit from-clause first!!!
		treatHandlerStack.push( new TreatHandlerFromClause() );
		try {
			sqmQuerySpec.setFromClause( visitFromClause( (HqlParser.FromClauseContext) ctx.getChild( fromIndex ) ) );
		}
		finally {
			treatHandlerStack.pop();
		}

		final SqmSelectClause selectClause;
		if ( fromIndex == 1 ) {
			selectClause = visitSelectClause( (HqlParser.SelectClauseContext) ctx.getChild( 0 ) );
		}
		else if ( ctx.getChild( ctx.getChildCount() - 1 ) instanceof HqlParser.SelectClauseContext ) {
			selectClause = visitSelectClause( (HqlParser.SelectClauseContext) ctx.getChild( ctx.getChildCount() - 1 ) );
		}
		else {
			if ( creationOptions.useStrictJpaCompliance() ) {
				throw new StrictJpaComplianceViolation(
						"Encountered implicit select-clause, but strict JPQL compliance was requested",
						StrictJpaComplianceViolation.Type.IMPLICIT_SELECT
				);
			}
			log.debugf( "Encountered implicit select clause : %s", ctx.getText() );
			selectClause = buildInferredSelectClause( sqmQuerySpec.getFromClause() );
		}
		sqmQuerySpec.setSelectClause( selectClause );

		int currentIndex = fromIndex + 1;
		final SqmWhereClause whereClause = new SqmWhereClause( creationContext.getNodeBuilder() );
		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.WhereClauseContext ) {
			treatHandlerStack.push( new TreatHandlerNormal( DowncastLocation.WHERE ) );
			try {
				whereClause.setPredicate( (SqmPredicate) ctx.getChild( currentIndex++ ).accept( this ) );
			}
			finally {
				treatHandlerStack.pop();
			}
		}
		sqmQuerySpec.setWhereClause( whereClause );

		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.GroupByClauseContext ) {
			sqmQuerySpec.setGroupByClauseExpressions(
					visitGroupByClause( (HqlParser.GroupByClauseContext) ctx.getChild( currentIndex++ ) )
			);
		}
		if ( currentIndex < ctx.getChildCount() && ctx.getChild( currentIndex ) instanceof HqlParser.HavingClauseContext ) {
			sqmQuerySpec.setHavingClausePredicate(
					visitHavingClause( (HqlParser.HavingClauseContext) ctx.getChild( currentIndex ) )
			);
		}

		return sqmQuerySpec;
	}

	@SuppressWarnings("WeakerAccess")
	protected SqmSelectClause buildInferredSelectClause(SqmFromClause fromClause) {
		// for now, this is slightly different than the legacy behavior where
		// the root and each non-fetched-join was selected.  For now, here, we simply
		// select the root
		final SqmSelectClause selectClause = new SqmSelectClause(
				false,
				fromClause.getNumberOfRoots(),
				creationContext.getNodeBuilder()
		);

		fromClause.visitRoots(
				sqmRoot -> selectClause.addSelection(
						new SqmSelection<>( sqmRoot, sqmRoot.getExplicitAlias(), creationContext.getNodeBuilder() )
				)
		);
		return selectClause;
	}

	@Override
	public SqmSelectClause visitSelectClause(HqlParser.SelectClauseContext ctx) {
		treatHandlerStack.push( new TreatHandlerNormal( DowncastLocation.SELECT ) );

		// todo (6.0) : primer a select-clause-specific SemanticPathPart into the stack
		final int selectionListIndex;
		if ( ctx.getChild( 1 ) instanceof HqlParser.SelectionListContext ) {
			selectionListIndex = 1;
		}
		else {
			selectionListIndex = 2;
		}

		try {
			final SqmSelectClause selectClause = new SqmSelectClause(
					selectionListIndex == 2,
					creationContext.getNodeBuilder()
			);
			final HqlParser.SelectionListContext selectionListContext = (HqlParser.SelectionListContext) ctx.getChild(
					selectionListIndex
			);
			for ( ParseTree subCtx : selectionListContext.children ) {
				if ( subCtx instanceof HqlParser.SelectionContext ) {
					selectClause.addSelection( visitSelection( (HqlParser.SelectionContext) subCtx ) );
				}
			}
			return selectClause;
		}
		finally {
			treatHandlerStack.pop();
		}
	}

	@Override
	public SqmSelection<?> visitSelection(HqlParser.SelectionContext ctx) {
		final String resultIdentifier;
		if ( ctx.getChildCount() == 1 ) {
			resultIdentifier = null;
		}
		else {
			resultIdentifier = applyJpaCompliance(
					visitResultIdentifier( (HqlParser.ResultIdentifierContext) ctx.getChild( 1 ) )
			);
		}
		final SqmSelectableNode<?> selectableNode = visitSelectableNode( ctx );

		final SqmSelection<?> selection = new SqmSelection<>(
				selectableNode,
				// NOTE : SqmSelection forces the alias down to its selectableNode.
				//		- no need to do that here
				resultIdentifier,
				creationContext.getNodeBuilder()
		);

		// if the node is not a dynamic-instantiation, register it with
		// the path-registry
		//noinspection StatementWithEmptyBody
		if ( selectableNode instanceof SqmDynamicInstantiation ) {
			// nothing else to do (avoid kludgy `! ( instanceof )` syntax
		}
		else {
			getCurrentProcessingState().getPathRegistry().register( selection );
		}

		return selection;
	}

	private SqmSelectableNode<?> visitSelectableNode(HqlParser.SelectionContext ctx) {
		final ParseTree subCtx = ctx.getChild( 0 ).getChild( 0 );
		if ( subCtx instanceof HqlParser.DynamicInstantiationContext ) {
			return visitDynamicInstantiation( (HqlParser.DynamicInstantiationContext) subCtx );
		}
		else if ( subCtx instanceof HqlParser.JpaSelectObjectSyntaxContext ) {
			return visitJpaSelectObjectSyntax( (HqlParser.JpaSelectObjectSyntaxContext) subCtx );
		}
		else if ( subCtx instanceof HqlParser.MapEntrySelectionContext ) {
			return visitMapEntrySelection( (HqlParser.MapEntrySelectionContext) subCtx );
		}
		else if ( subCtx instanceof HqlParser.ExpressionContext ) {
			final SqmExpression<?> sqmExpression = (SqmExpression<?>) subCtx.accept( this );
			if ( sqmExpression instanceof SqmPath ) {
				final SqmPath<?> sqmPath = (SqmPath<?>) sqmExpression;
				if ( sqmPath.getReferencedPathSource() instanceof PluralPersistentAttribute ) {
					// for plural-attribute selections, use the element path as the selection
					//		- this is not strictly JPA compliant
					if ( creationOptions.useStrictJpaCompliance() ) {
						SqmTreeCreationLogger.LOGGER.debugf(
								"Raw selection of plural attribute not supported by JPA: %s.  Use `value(%s)` or `key(%s)` to indicate what part of the collection to select",
								sqmPath.getAlias(),
								sqmPath.getAlias(),
								sqmPath.getAlias()
						);
					}

					final PluralPersistentAttribute<?, ?, ?> pluralAttribute = (PluralPersistentAttribute<?, ?, ?>) sqmPath.getReferencedPathSource();
					final SqmPath<?> elementPath = pluralAttribute.getElementPathSource().createSqmPath( sqmPath );
					processingStateStack.getCurrent().getPathRegistry().register( elementPath );
					return elementPath;
				}
			}

			return sqmExpression;
		}

		throw new ParsingException( "Unexpected selection rule type : " + ctx.getText() );
	}

	@Override
	public String visitResultIdentifier(HqlParser.ResultIdentifierContext resultIdentifierContext) {
		if ( resultIdentifierContext != null ) {
			if ( resultIdentifierContext.getChildCount() == 1 ) {
				return resultIdentifierContext.getText();
			}
			else {
				final HqlParser.IdentifierContext identifierContext = (HqlParser.IdentifierContext) resultIdentifierContext.getChild( 1 );
				final Token aliasToken = identifierContext.getStart();
				final String explicitAlias = aliasToken.getText();

				if ( aliasToken.getType() != IDENTIFIER ) {
					// we have a reserved word used as an identification variable.
					if ( creationOptions.useStrictJpaCompliance() ) {
						throw new StrictJpaComplianceViolation(
								String.format(
										Locale.ROOT,
										"Strict JPQL compliance was violated : %s [%s]",
										StrictJpaComplianceViolation.Type.RESERVED_WORD_USED_AS_ALIAS.description(),
										explicitAlias
								),
								StrictJpaComplianceViolation.Type.RESERVED_WORD_USED_AS_ALIAS
						);
					}
				}
				return explicitAlias;
			}
		}

		return null;
	}

	@Override
	public SqmDynamicInstantiation<?> visitDynamicInstantiation(HqlParser.DynamicInstantiationContext ctx) {
		final SqmDynamicInstantiation<?> dynamicInstantiation;
		final ParseTree instantiationTarget = ctx.dynamicInstantiationTarget().getChild( 0 );
		if ( instantiationTarget instanceof HqlParser.DotIdentifierSequenceContext ) {
			final String className = instantiationTarget.getText();
			try {
				final JavaTypeDescriptor<?> jtd = resolveInstantiationTargetJtd( className );
				dynamicInstantiation = SqmDynamicInstantiation.forClassInstantiation(
						jtd,
						creationContext.getNodeBuilder()
				);
			}
			catch (ClassLoadingException e) {
				throw new SemanticException( "Unable to resolve class named for dynamic instantiation : " + className );
			}
		}
		else {
			final TerminalNode terminalNode = (TerminalNode) instantiationTarget;
			switch ( terminalNode.getSymbol().getType() ) {
				case HqlParser.MAP:
					dynamicInstantiation = SqmDynamicInstantiation.forMapInstantiation(
							mapJavaTypeDescriptor,
							creationContext.getNodeBuilder()
					);
					break;
				case HqlParser.LIST:
					dynamicInstantiation = SqmDynamicInstantiation.forListInstantiation(
							listJavaTypeDescriptor,
							creationContext.getNodeBuilder()
					);
					break;
				default:
					throw new UnsupportedOperationException( "Unsupported instantiation target: " + terminalNode );
			}
		}

		for ( HqlParser.DynamicInstantiationArgContext arg : ctx.dynamicInstantiationArgs().dynamicInstantiationArg() ) {
			dynamicInstantiation.addArgument( visitDynamicInstantiationArg( arg ) );
		}

		return dynamicInstantiation;
	}

	private JavaTypeDescriptor<?> resolveInstantiationTargetJtd(String className) {
		final Class<?> targetJavaType = classForName( creationContext.getJpaMetamodel().qualifyImportableName( className ) );
		return creationContext.getJpaMetamodel()
				.getTypeConfiguration()
				.getJavaTypeDescriptorRegistry()
				.resolveDescriptor( targetJavaType );
	}

	private Class<?> classForName(String className) {
		return creationContext.getServiceRegistry().getService( ClassLoaderService.class ).classForName( className );
	}

	@Override
	public SqmDynamicInstantiationArgument<?> visitDynamicInstantiationArg(HqlParser.DynamicInstantiationArgContext ctx) {
		final String alias;
		if ( ctx.getChildCount() > 1 ) {
			alias = ctx.getChild( ctx.getChildCount() - 1 ).getText();
		}
		else {
			alias = null;
		}

		final SqmSelectableNode<?> argExpression = visitDynamicInstantiationArgExpression(
				(HqlParser.DynamicInstantiationArgExpressionContext) ctx.getChild( 0 )
		);

		final SqmDynamicInstantiationArgument<?> argument = new SqmDynamicInstantiationArgument<>(
				argExpression,
				alias,
				creationContext.getNodeBuilder()
		);

		//noinspection StatementWithEmptyBody
		if ( argExpression instanceof SqmDynamicInstantiation ) {
			// nothing else to do (avoid kludgy `! ( instanceof )` syntax
		}
		else {
			getCurrentProcessingState().getPathRegistry().register( argument );
		}

		return argument;
	}

	@Override
	public SqmSelectableNode<?> visitDynamicInstantiationArgExpression(HqlParser.DynamicInstantiationArgExpressionContext ctx) {
		final ParseTree parseTree = ctx.getChild( 0 );
		if ( parseTree instanceof HqlParser.DynamicInstantiationContext ) {
			return visitDynamicInstantiation( (HqlParser.DynamicInstantiationContext) parseTree );
		}
		else if ( parseTree instanceof HqlParser.ExpressionContext ) {
			return (SqmExpression<?>) parseTree.accept( this );
		}

		throw new ParsingException( "Unexpected dynamic-instantiation-argument rule type : " + ctx.getText() );
	}

	@Override
	public SqmPath<?> visitJpaSelectObjectSyntax(HqlParser.JpaSelectObjectSyntaxContext ctx) {
		final String alias = ctx.getChild( 2 ).getText();
		final SqmFrom<?, ?> sqmFromByAlias = processingStateStack.getCurrent().getPathRegistry().findFromByAlias( alias );
		if ( sqmFromByAlias == null ) {
			throw new SemanticException( "Unable to resolve alias [" +  alias + "] in selection [" + ctx.getText() + "]" );
		}
		return sqmFromByAlias;
	}

	@Override
	public List<SqmExpression<?>> visitGroupByClause(HqlParser.GroupByClauseContext ctx) {
		final int size = ctx.getChildCount();
		// Shift 1 bit instead of division by 2
		final int estimateExpressionsCount = ( size >> 1 ) - 1;
		final List<SqmExpression<?>> expressions = new ArrayList<>( estimateExpressionsCount );
		for ( int i = 0; i < size; i++ ) {
			final ParseTree parseTree = ctx.getChild( i );
			if ( parseTree instanceof HqlParser.GroupByExpressionContext ) {
				expressions.add( (SqmExpression<?>) parseTree.accept( this ) );
			}
		}
		return expressions;
	}

	private SqmExpression<?> resolveOrderByOrGroupByExpression(ParseTree child, boolean definedCollate) {
		if ( child instanceof TerminalNode ) {
			if ( definedCollate ) {
				// This is syntactically disallowed
				throw new ParsingException( "COLLATE is not allowed for position based order-by or group-by items" );
			}

			final int position = Integer.parseInt( child.getText() );

			// make sure this selection exists
			final SqmAliasedNode<?> nodeByPosition = getCurrentProcessingState()
					.getPathRegistry()
					.findAliasedNodeByPosition( position );
			if ( nodeByPosition == null ) {
				throw new ParsingException( "Numeric literal `" + position + "` used in group-by does not match a registered select-item" );
			}

			return new SqmAliasedNodeRef( position, integerDomainType, creationContext.getNodeBuilder() );
		}
		else if ( child instanceof HqlParser.IdentifierContext ) {
			final String identifierText = child.getText();

			final Integer correspondingPosition = getCurrentProcessingState()
					.getPathRegistry()
					.findAliasedNodePosition( identifierText );
			if ( correspondingPosition != null ) {
				if ( definedCollate ) {
					// This is syntactically disallowed
					throw new ParsingException( "COLLATE is not allowed for alias based order-by or group-by items" );
				}
				return new SqmAliasedNodeRef( correspondingPosition, integerDomainType, creationContext.getNodeBuilder() );
			}

			final SqmFrom<?, ?> sqmFrom = getCurrentProcessingState().getPathRegistry().findFromByAlias( identifierText );
			if ( sqmFrom != null ) {
				if ( definedCollate ) {
					// This is syntactically disallowed
					throw new ParsingException( "COLLATE is not allowed for alias based order-by or group-by items" );
				}
				// this will group-by all of the sub-parts in the from-element's model part
				return sqmFrom;
			}

			final DotIdentifierConsumer dotIdentifierConsumer = dotIdentifierConsumerStack.getCurrent();
			dotIdentifierConsumer.consumeIdentifier( identifierText, true, true );
			return (SqmExpression<?>) dotIdentifierConsumer.getConsumedPart();
		}

		return (SqmExpression<?>) child.accept( this );
	}

	@Override
	public SqmExpression<?> visitGroupByExpression(HqlParser.GroupByExpressionContext ctx) {
		return resolveOrderByOrGroupByExpression( ctx.getChild( 0 ), ctx.getChildCount() > 1 );
	}

	@Override
	public SqmPredicate visitHavingClause(HqlParser.HavingClauseContext ctx) {
		return (SqmPredicate) ctx.getChild( 1 ).accept( this );
	}

	@Override
	public SqmOrderByClause visitOrderByClause(HqlParser.OrderByClauseContext ctx) {
		final int size = ctx.getChildCount();
		// Shift 1 bit instead of division by 2
		final int estimateExpressionsCount = ( size >> 1 ) - 1;
		final SqmOrderByClause orderByClause = new SqmOrderByClause( estimateExpressionsCount );
		for ( int i = 0; i < size; i++ ) {
			final ParseTree parseTree = ctx.getChild( i );
			if ( parseTree instanceof HqlParser.SortSpecificationContext ) {
				orderByClause.addSortSpecification(
						visitSortSpecification( (HqlParser.SortSpecificationContext) parseTree )
				);
			}
		}
		return orderByClause;
	}

	@Override
	public SqmSortSpecification visitSortSpecification(HqlParser.SortSpecificationContext ctx) {
		final SqmExpression<?> sortExpression = visitSortExpression( (HqlParser.SortExpressionContext) ctx.getChild( 0 ) );
		if ( sortExpression == null ) {
			throw new ParsingException( "Could not resolve sort-expression : " + ctx.getChild( 0 ).getText() );
		}
		if ( sortExpression instanceof SqmLiteral || sortExpression instanceof SqmParameter ) {
			HqlLogging.QUERY_LOGGER.debugf( "Questionable sorting by constant value : %s", sortExpression );
		}

		final SortOrder sortOrder;
		final NullPrecedence nullPrecedence;
		int nextIndex = 1;
		if ( nextIndex < ctx.getChildCount() ) {
			ParseTree parseTree = ctx.getChild( nextIndex );
			if ( parseTree instanceof HqlParser.OrderingSpecificationContext ) {
				switch ( ( (TerminalNode) parseTree.getChild( 0 ) ).getSymbol().getType() ) {
					case HqlParser.ASC:
						sortOrder = SortOrder.ASCENDING;
						break;
					case HqlParser.DESC:
						sortOrder = SortOrder.DESCENDING;
						break;
					default:
						throw new SemanticException( "Unrecognized sort ordering: " + parseTree.getText() );
				}
				nextIndex++;
			}
			else {
				sortOrder = null;
			}
			parseTree = ctx.getChild( nextIndex );
			if ( parseTree instanceof HqlParser.NullsPrecedenceContext ) {
				switch ( ( (TerminalNode) parseTree.getChild( 1 ) ).getSymbol().getType() ) {
					case HqlParser.FIRST:
						nullPrecedence = NullPrecedence.FIRST;
						break;
					case HqlParser.LAST:
						nullPrecedence = NullPrecedence.LAST;
						break;
					default:
						throw new SemanticException( "Unrecognized null precedence: " + parseTree.getText() );
				}
			}
			else {
				nullPrecedence = null;
			}
		}
		else {
			sortOrder = null;
			nullPrecedence = null;
		}

		return new SqmSortSpecification( sortExpression, sortOrder, nullPrecedence );
	}

	@Override
	public SqmExpression<?> visitSortExpression(HqlParser.SortExpressionContext ctx) {
		return resolveOrderByOrGroupByExpression( ctx.getChild( 0 ), ctx.getChildCount() > 1 );
	}

	private SqmQuerySpec<?> currentQuerySpec() {
		SqmQuery<?> processingQuery = processingStateStack.getCurrent().getProcessingQuery();
		if ( processingQuery instanceof SqmInsertSelectStatement<?> ) {
			return ( (SqmInsertSelectStatement<?>) processingQuery ).getSelectQueryPart().getLastQuerySpec();
		}
		else {
			return ( (SqmSelectQuery<?>) processingQuery ).getQueryPart().getLastQuerySpec();
		}
	}

	private <X> void setCurrentQueryPart(SqmQueryPart<X> queryPart) {
		@SuppressWarnings("unchecked")
		final SqmQuery<X> processingQuery = (SqmQuery<X>) processingStateStack.getCurrent().getProcessingQuery();
		if ( processingQuery instanceof SqmInsertSelectStatement<?> ) {
			( (SqmInsertSelectStatement<X>) processingQuery ).setSelectQueryPart( queryPart );
		}
		else {
			( (AbstractSqmSelectQuery<X>) processingQuery ).setQueryPart( queryPart );
		}
	}

	private int getSelectionPosition(SqmSelection<?> selection) {
		return currentQuerySpec().getSelectClause().getSelections().indexOf( selection ) + 1;
	}

	@Override
	public SqmExpression<?> visitLimitClause(HqlParser.LimitClauseContext ctx) {
		if ( ctx == null ) {
			return null;
		}

		return (SqmExpression<?>) ctx.getChild( 1 ).accept( this );
	}

	@Override
	public SqmExpression<?> visitOffsetClause(HqlParser.OffsetClauseContext ctx) {
		if ( ctx == null ) {
			return null;
		}

		return (SqmExpression<?>) ctx.getChild( 1 ).accept( this );
	}

	@Override
	public SqmExpression<?> visitFetchClause(HqlParser.FetchClauseContext ctx) {
		if ( ctx == null ) {
			return null;
		}

		return (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
	}

	private FetchClauseType visitFetchClauseType(HqlParser.FetchClauseContext ctx) {
		if ( ctx == null ) {
			return FetchClauseType.ROWS_ONLY;
		}
		final int thirdSymbolType = ( (TerminalNode) ctx.getChild( 3 ) ).getSymbol().getType();
		final int lastSymbolType = ( (TerminalNode) ctx.getChild( ctx.getChildCount() - 1 ) ).getSymbol().getType();
		if ( lastSymbolType == HqlParser.TIES ) {
			return thirdSymbolType == HqlParser.PERCENT ? FetchClauseType.PERCENT_WITH_TIES : FetchClauseType.ROWS_WITH_TIES;
		}
		else {
			return thirdSymbolType == HqlParser.PERCENT ? FetchClauseType.PERCENT_ONLY : FetchClauseType.ROWS_ONLY;
		}
	}

	@Override
	public SqmExpression<?> visitPathExpression(HqlParser.PathExpressionContext ctx) {
		final HqlParser.PathContext path = (HqlParser.PathContext) ctx.getChild( 0 );
		final Object accept = path.accept( this );
		if ( accept instanceof DomainPathPart ) {
			return ( (DomainPathPart) accept ).getSqmExpression();
		}
		return (SqmExpression<?>) accept;
	}

	@Override
	public SqmExpression<?> visitFunctionExpression(HqlParser.FunctionExpressionContext ctx) {
		return (SqmExpression<?>) ctx.getChild( 0 ).accept( this );
	}

	@Override
	public SqmExpression<?> visitParameterOrIntegerLiteral(HqlParser.ParameterOrIntegerLiteralContext ctx) {
		final ParseTree firstChild = ctx.getChild( 0 );
		if ( firstChild instanceof TerminalNode ) {
			return integerLiteral( firstChild.getText() );
		}
		return (SqmExpression<?>) firstChild.accept( this );
	}

	@Override
	public SqmExpression<?> visitParameterOrNumberLiteral(HqlParser.ParameterOrNumberLiteralContext ctx) {
		if ( ctx.INTEGER_LITERAL() != null ) {
			return integerLiteral( ctx.INTEGER_LITERAL().getText() );
		}
		if ( ctx.FLOAT_LITERAL() != null ) {
			return floatLiteral( ctx.FLOAT_LITERAL().getText() );
		}
		if ( ctx.DOUBLE_LITERAL() != null ) {
			return doubleLiteral( ctx.DOUBLE_LITERAL().getText() );
		}
		if ( ctx.parameter() != null ) {
			return (SqmExpression<?>) ctx.parameter().accept( this );
		}
		final ParseTree firstChild = ctx.getChild( 0 );
		if ( firstChild instanceof TerminalNode ) {
			switch ( ( (TerminalNode) firstChild ).getSymbol().getType() ) {
				case HqlParser.INTEGER_LITERAL:
					return integerLiteral( firstChild.getText() );
				case HqlParser.FLOAT_LITERAL:
					return floatLiteral( firstChild.getText() );
				case HqlParser.DOUBLE_LITERAL:
					return doubleLiteral( firstChild.getText() );
				default:
					throw new UnsupportedOperationException( "Unsupported literal: " + firstChild.getText() );
			}
		}
		return (SqmExpression<?>) firstChild.accept( this );
	}

	@Override
	public EntityDomainType<?> visitEntityName(HqlParser.EntityNameContext parserEntityName) {
		final String entityName = parserEntityName.fullNameText;
		final EntityDomainType<?> entityReference = resolveEntityReference( entityName );
		if ( entityReference == null ) {
			throw new UnknownEntityException( "Could not resolve entity name [" + entityName + "] as DML target", entityName );
		}
		checkFQNEntityNameJpaComplianceViolationIfNeeded( entityName, entityReference );
		return entityReference;
	}

	private EntityDomainType<?> resolveEntityReference(String entityName) {
		log.debugf( "Attempting to resolve path [%s] as entity reference...", entityName );
		EntityDomainType<?> reference = null;
		try {
			entityName = creationContext.getJpaMetamodel().qualifyImportableName( entityName );
			reference = creationContext.getJpaMetamodel().entity( entityName );
		}
		catch (Exception ignore) {
		}

		return reference;
	}


	@Override
	public SqmFromClause visitFromClause(HqlParser.FromClauseContext parserFromClause) {
		treatHandlerStack.push( new TreatHandlerFromClause() );

		try {
			final SqmFromClause fromClause;
			if ( parserFromClause == null ) {
				fromClause = new SqmFromClause();
			}
			else {
				final int size = parserFromClause.getChildCount();
				// Shift 1 bit instead of division by 2
				final int estimatedSize = size >> 1;
				fromClause = new SqmFromClause( estimatedSize );
				for ( int i = 0; i < size; i++ ) {
					final ParseTree parseTree = parserFromClause.getChild( i );
					if ( parseTree instanceof HqlParser.FromClauseSpaceContext ) {
						fromClause.addRoot( visitFromClauseSpace( (HqlParser.FromClauseSpaceContext) parseTree ) );
					}
				}
			}
			return fromClause;
		}
		finally {
			treatHandlerStack.pop();
		}
	}

	@Override
	public SqmRoot<?> visitFromClauseSpace(HqlParser.FromClauseSpaceContext parserSpace) {
		final SqmRoot<?> sqmRoot = visitPathRoot( (HqlParser.PathRootContext) parserSpace.getChild( 0 ) );
		final int size = parserSpace.getChildCount();
		for ( int i = 1; i < size; i++ ) {
			final ParseTree parseTree = parserSpace.getChild( i );
			if ( parseTree instanceof HqlParser.CrossJoinContext ) {
				consumeCrossJoin( (HqlParser.CrossJoinContext) parseTree, sqmRoot );
			}
			else if ( parseTree instanceof HqlParser.QualifiedJoinContext ) {
				consumeQualifiedJoin( (HqlParser.QualifiedJoinContext) parseTree, sqmRoot );
			}
			else if ( parseTree instanceof HqlParser.JpaCollectionJoinContext ) {
				consumeJpaCollectionJoin( (HqlParser.JpaCollectionJoinContext) parseTree, sqmRoot );
			}
		}

		return sqmRoot;
	}

	@Override
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public SqmRoot<?> visitPathRoot(HqlParser.PathRootContext ctx) {
		final HqlParser.EntityNameContext entityNameContext = (HqlParser.EntityNameContext) ctx.getChild( 0 );
		final List<ParseTree> entityNameParseTreeChildren = entityNameContext.children;
		final String name = entityNameContext.fullNameText;

		log.debugf( "Handling root path - %s", name );
		final EntityDomainType entityDescriptor = getCreationContext()
				.getJpaMetamodel()
				.getHqlEntityReference( name );

		final HqlParser.IdentificationVariableDefContext identificationVariableDefContext;
		if ( ctx.getChildCount() > 1 ) {
			identificationVariableDefContext = (HqlParser.IdentificationVariableDefContext) ctx.getChild( 1 );
		}
		else {
			identificationVariableDefContext = null;
		}
		final String alias = applyJpaCompliance(
				visitIdentificationVariableDef( identificationVariableDefContext )
		);

		final SqmCreationProcessingState processingState = processingStateStack.getCurrent();
		final SqmPathRegistry pathRegistry = processingState.getPathRegistry();
		if ( entityDescriptor == null ) {
			final int size = entityNameParseTreeChildren.size();
			// Handle the use of a correlation path in subqueries
			if ( processingStateStack.depth() > 1 && size > 2 ) {
				final String parentAlias = entityNameParseTreeChildren.get( 0 ).getText();
				final AbstractSqmFrom<?, ?> correlationBasis = (AbstractSqmFrom<?, ?>) processingState.getParentProcessingState()
						.getPathRegistry()
						.findFromByAlias( parentAlias );
				if ( correlationBasis != null ) {
					final SqmCorrelation<?, ?> correlation = correlationBasis.createCorrelation();
					pathRegistry.register( correlation );
					final DotIdentifierConsumer dotIdentifierConsumer = new QualifiedJoinPathConsumer(
							correlation,
							SqmJoinType.INNER,
							false,
							alias,
							this
					);
					final int lastIdx = size - 1;
					for ( int i = 2; i != lastIdx; i += 2 ) {
						dotIdentifierConsumer.consumeIdentifier(
								entityNameParseTreeChildren.get( i ).getText(),
								false,
								false
						);
					}
					dotIdentifierConsumer.consumeIdentifier(
							entityNameParseTreeChildren.get( lastIdx ).getText(),
							false,
							true
					);
					return correlation.getCorrelatedRoot();
				}
				throw new IllegalArgumentException( "Could not resolve entity reference or correlation path: " + name );
			}
			throw new IllegalArgumentException( "Could not resolve entity reference: " + name );
		}
		checkFQNEntityNameJpaComplianceViolationIfNeeded( name, entityDescriptor );

		if ( entityDescriptor instanceof SqmPolymorphicRootDescriptor ) {
			if ( getCreationOptions().useStrictJpaCompliance() ) {
				throw new StrictJpaComplianceViolation(
						"Encountered unmapped polymorphic reference [" + entityDescriptor.getHibernateEntityName()
								+ "], but strict JPQL compliance was requested",
						StrictJpaComplianceViolation.Type.UNMAPPED_POLYMORPHISM
				);
			}

			if ( processingStateStack.depth() > 1 ) {
				throw new SemanticException(
						"Illegal implicit-polymorphic domain path in sub-query : " + entityDescriptor.getName()
				);
			}
		}

		final SqmRoot<?> sqmRoot = new SqmRoot<>( entityDescriptor, alias, creationContext.getNodeBuilder() );

		pathRegistry.register( sqmRoot );

		return sqmRoot;
	}

	@Override
	public String visitIdentificationVariableDef(HqlParser.IdentificationVariableDefContext ctx) {
		if ( ctx == null ) {
			return null;
		}
		final ParseTree lastChild = ctx.getChild( ctx.getChildCount() - 1 );
		if ( lastChild instanceof HqlParser.IdentifierContext ) {
			final HqlParser.IdentifierContext identifierContext = (HqlParser.IdentifierContext) lastChild;
			// in this branch, the alias could be a reserved word ("keyword as identifier")
			// which JPA disallows...
			if ( getCreationOptions().useStrictJpaCompliance() ) {
				final Token identificationVariableToken = identifierContext.getStart();
				if ( identificationVariableToken.getType() != IDENTIFIER ) {
					throw new StrictJpaComplianceViolation(
							String.format(
									Locale.ROOT,
									"Strict JPQL compliance was violated : %s [%s]",
									StrictJpaComplianceViolation.Type.RESERVED_WORD_USED_AS_ALIAS.description(),
									identificationVariableToken.getText()
							),
							StrictJpaComplianceViolation.Type.RESERVED_WORD_USED_AS_ALIAS
					);
				}
			}
			return identifierContext.getText();
		}
		else {
			return lastChild.getText();
		}
	}

	private String applyJpaCompliance(String text) {
		if ( text == null ) {
			return null;
		}

		if ( getCreationOptions().useStrictJpaCompliance() ) {
			return text.toLowerCase( Locale.getDefault() );
		}

		return text;
	}

	@Override
	public final SqmCrossJoin<?> visitCrossJoin(HqlParser.CrossJoinContext ctx) {
		throw new UnsupportedOperationException( "Unexpected call to #visitCrossJoin, see #consumeCrossJoin" );
	}

	private <T> void consumeCrossJoin(HqlParser.CrossJoinContext parserJoin, SqmRoot<T> sqmRoot) {
		final HqlParser.PathRootContext pathRootContext = (HqlParser.PathRootContext) parserJoin.getChild( 2 );
		final HqlParser.EntityNameContext entityNameContext = (HqlParser.EntityNameContext) pathRootContext.getChild( 0 );
		final String name = entityNameContext.fullNameText;

		SqmTreeCreationLogger.LOGGER.debugf( "Handling root path - %s", name );

		final EntityDomainType<T> entityDescriptor = getCreationContext().getJpaMetamodel()
				.resolveHqlEntityReference( name );

		if ( entityDescriptor instanceof SqmPolymorphicRootDescriptor ) {
			throw new SemanticException( "Unmapped polymorphic reference cannot be used as a CROSS JOIN target" );
		}
		final HqlParser.IdentificationVariableDefContext identificationVariableDefContext;
		if ( pathRootContext.getChildCount() > 1 ) {
			identificationVariableDefContext = (HqlParser.IdentificationVariableDefContext) pathRootContext.getChild( 1 );
		}
		else {
			identificationVariableDefContext = null;
		}
		final SqmCrossJoin<T> join = new SqmCrossJoin<>(
				entityDescriptor,
				visitIdentificationVariableDef( identificationVariableDefContext ),
				sqmRoot
		);

		processingStateStack.getCurrent().getPathRegistry().register( join );

		// CROSS joins are always added to the root
		sqmRoot.addSqmJoin( join );
	}

	@Override
	public final SqmQualifiedJoin<?, ?> visitQualifiedJoin(HqlParser.QualifiedJoinContext parserJoin) {
		throw new UnsupportedOperationException( "Unexpected call to #visitQualifiedJoin, see #consumeQualifiedJoin" );
	}

	@SuppressWarnings("WeakerAccess")
	protected void consumeQualifiedJoin(HqlParser.QualifiedJoinContext parserJoin, SqmRoot<?> sqmRoot) {
		final SqmJoinType joinType;
		final int firstJoinTypeSymbolType;
		if ( parserJoin.getChild( 0 ) instanceof HqlParser.JoinTypeQualifierContext
				&& parserJoin.getChild( 0 ).getChildCount() != 0 ) {
			firstJoinTypeSymbolType = ( (TerminalNode) parserJoin.getChild( 0 ).getChild( 0 ) ).getSymbol().getType();
		}
		else {
			firstJoinTypeSymbolType = HqlParser.INNER;
		}
		switch ( firstJoinTypeSymbolType ) {
			case HqlParser.FULL:
				joinType = SqmJoinType.FULL;
				break;
			case HqlParser.RIGHT:
				joinType = SqmJoinType.RIGHT;
				break;
			// For some reason, we also support `outer join` syntax..
			case HqlParser.OUTER:
			case HqlParser.LEFT:
				joinType = SqmJoinType.LEFT;
				break;
			default:
				joinType = SqmJoinType.INNER;
				break;
		}

		final HqlParser.QualifiedJoinRhsContext qualifiedJoinRhsContext = parserJoin.qualifiedJoinRhs();
		final HqlParser.IdentificationVariableDefContext identificationVariableDefContext;
		if ( qualifiedJoinRhsContext.getChildCount() > 1 ) {
			identificationVariableDefContext = (HqlParser.IdentificationVariableDefContext) qualifiedJoinRhsContext.getChild( 1 );
		}
		else {
			identificationVariableDefContext = null;
		}
		final String alias = visitIdentificationVariableDef( identificationVariableDefContext );

		dotIdentifierConsumerStack.push(
				new QualifiedJoinPathConsumer(
						sqmRoot,
						joinType,
						parserJoin.getChild( 2 ) instanceof TerminalNode,
						alias,
						this
				)
		);

		try {
			//noinspection rawtypes
			final SqmQualifiedJoin join = (SqmQualifiedJoin) qualifiedJoinRhsContext.getChild( 0 ).accept( this );

			// we need to set the alias here because the path could be treated - the treat operator is
			// not consumed by the identifierConsumer
			join.setExplicitAlias( alias );

			if ( join instanceof SqmEntityJoin ) {
				//noinspection unchecked
				sqmRoot.addSqmJoin( join );
			}
			else {
				if ( getCreationOptions().useStrictJpaCompliance() ) {
					if ( join.getExplicitAlias() != null ){
						//noinspection rawtypes
						if ( ( (SqmAttributeJoin) join ).isFetched() ) {
							throw new StrictJpaComplianceViolation(
									"Encountered aliased fetch join, but strict JPQL compliance was requested",
									StrictJpaComplianceViolation.Type.ALIASED_FETCH_JOIN
							);
						}
					}
				}
			}

			final HqlParser.QualifiedJoinPredicateContext qualifiedJoinPredicateContext = parserJoin.qualifiedJoinPredicate();
			if ( qualifiedJoinPredicateContext != null ) {
				dotIdentifierConsumerStack.push( new QualifiedJoinPredicatePathConsumer( join, this ) );
				try {
					join.setJoinPredicate( (SqmPredicate) qualifiedJoinPredicateContext.getChild( 1 ).accept( this ) );
				}
				finally {
					dotIdentifierConsumerStack.pop();
				}
			}
		}
		finally {
			dotIdentifierConsumerStack.pop();
		}
	}

	@Override
	public SqmJoin<?, ?> visitJpaCollectionJoin(HqlParser.JpaCollectionJoinContext ctx) {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("WeakerAccess")
	protected void consumeJpaCollectionJoin(
			HqlParser.JpaCollectionJoinContext ctx,
			SqmRoot<?> sqmRoot) {
		final HqlParser.IdentificationVariableDefContext identificationVariableDefContext;
		if ( ctx.getChildCount() > 1 ) {
			identificationVariableDefContext = (HqlParser.IdentificationVariableDefContext) ctx.getChild( 1 );
		}
		else {
			identificationVariableDefContext = null;
		}
		final String alias = visitIdentificationVariableDef( identificationVariableDefContext );
		dotIdentifierConsumerStack.push(
				new QualifiedJoinPathConsumer(
						sqmRoot,
						// According to JPA spec 4.4.6 this is an inner join
						SqmJoinType.INNER,
						false,
						alias,
						this
				)
		);

		try {
			consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 3 ) );
		}
		finally {
			dotIdentifierConsumerStack.pop();
		}
	}


	// Predicates (and `whereClause`)

	@Override
	public SqmPredicate visitWhereClause(HqlParser.WhereClauseContext ctx) {
		if ( ctx == null || ctx.getChildCount() != 2 ) {
			return null;
		}

		return (SqmPredicate) ctx.getChild( 1 ).accept( this );

	}

	@Override
	public SqmGroupedPredicate visitGroupedPredicate(HqlParser.GroupedPredicateContext ctx) {
		return new SqmGroupedPredicate(
				(SqmPredicate) ctx.getChild( 1 ).accept( this ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmPredicate visitAndPredicate(HqlParser.AndPredicateContext ctx) {
		return new SqmAndPredicate(
				(SqmPredicate) ctx.getChild( 0 ).accept( this ),
				(SqmPredicate) ctx.getChild( 2 ).accept( this ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmPredicate visitOrPredicate(HqlParser.OrPredicateContext ctx) {
		return new SqmOrPredicate(
				(SqmPredicate) ctx.getChild( 0 ).accept( this ),
				(SqmPredicate) ctx.getChild( 2 ).accept( this ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmPredicate visitNegatedPredicate(HqlParser.NegatedPredicateContext ctx) {
		SqmPredicate predicate = (SqmPredicate) ctx.getChild( 1 ).accept( this );
		if ( predicate instanceof SqmNegatablePredicate ) {
			( (SqmNegatablePredicate) predicate ).negate();
			return predicate;
		}
		else {
			return new SqmNegatedPredicate( predicate, creationContext.getNodeBuilder() );
		}
	}

	@Override
	public SqmBetweenPredicate visitBetweenPredicate(HqlParser.BetweenPredicateContext ctx) {
		final boolean negated = ( (TerminalNode) ctx.getChild( 1 ) ).getSymbol().getType() == HqlParser.NOT;
		final int startIndex = negated ? 3 : 2;
		return new SqmBetweenPredicate(
				(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
				(SqmExpression<?>) ctx.getChild( startIndex ).accept( this ),
				(SqmExpression<?>) ctx.getChild( startIndex + 2 ).accept( this ),
				negated,
				creationContext.getNodeBuilder()
		);
	}


	@Override
	public SqmNullnessPredicate visitIsNullPredicate(HqlParser.IsNullPredicateContext ctx) {
		final boolean negated = ctx.getChildCount() == 4;
		return new SqmNullnessPredicate(
				(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
				negated,
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmEmptinessPredicate visitIsEmptyPredicate(HqlParser.IsEmptyPredicateContext ctx) {
		final boolean negated = ctx.getChildCount() == 4;
		return new SqmEmptinessPredicate(
				(SqmPluralValuedSimplePath<?>) ctx.getChild( 0 ).accept( this ),
				negated,
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public Object visitComparisonOperator(HqlParser.ComparisonOperatorContext ctx) {
		final TerminalNode firstToken = (TerminalNode) ctx.getChild( 0 );
		switch ( firstToken.getSymbol().getType() ) {
			case HqlLexer.EQUAL:
				return ComparisonOperator.EQUAL;
			case HqlLexer.NOT_EQUAL:
				return ComparisonOperator.NOT_EQUAL;
			case HqlLexer.LESS:
				return ComparisonOperator.LESS_THAN;
			case HqlLexer.LESS_EQUAL:
				return ComparisonOperator.LESS_THAN_OR_EQUAL;
			case HqlLexer.GREATER:
				return ComparisonOperator.GREATER_THAN;
			case HqlLexer.GREATER_EQUAL:
				return ComparisonOperator.GREATER_THAN_OR_EQUAL;
			case HqlLexer.IS: {
				final TerminalNode secondToken = (TerminalNode) ctx.getChild( 1 );
				return secondToken.getSymbol().getType() == HqlLexer.NOT
						? ComparisonOperator.NOT_DISTINCT_FROM
						: ComparisonOperator.DISTINCT_FROM;
			}
		}
		throw new QueryException("missing operator");
	}

	@Override
	public SqmPredicate visitComparisonPredicate(HqlParser.ComparisonPredicateContext ctx) {
		final ComparisonOperator comparisonOperator = (ComparisonOperator) ctx.getChild( 1 ).accept( this );
		final SqmExpression<?> left;
		final SqmExpression<?> right;
		final HqlParser.ExpressionContext leftExpressionContext = (HqlParser.ExpressionContext) ctx.getChild( 0 );
		final HqlParser.ExpressionContext rightExpressionContext = (HqlParser.ExpressionContext) ctx.getChild( 2 );
		switch (comparisonOperator) {
			case EQUAL:
			case NOT_EQUAL:
			case DISTINCT_FROM:
			case NOT_DISTINCT_FROM: {
				Map<Class<?>, Enum<?>> possibleEnumValues;
				if ( ( possibleEnumValues = getPossibleEnumValues( leftExpressionContext ) ) != null ) {
					right = (SqmExpression<?>) rightExpressionContext.accept( this );
					left = resolveEnumShorthandLiteral(
							leftExpressionContext,
							possibleEnumValues,
							right.getJavaType()
					);
					break;
				}
				else if ( ( possibleEnumValues = getPossibleEnumValues( rightExpressionContext ) ) != null ) {
					left = (SqmExpression<?>) leftExpressionContext.accept( this );
					right = resolveEnumShorthandLiteral(
							rightExpressionContext,
							possibleEnumValues,
							left.getJavaType()
					);
					break;
				}
				left = (SqmExpression<?>) leftExpressionContext.accept( this );
				right = (SqmExpression<?>) rightExpressionContext.accept( this );
				// This is something that we used to support before 6 which is also used in our testsuite
				if ( left instanceof SqmLiteralNull<?> ) {
					return new SqmNullnessPredicate(
							right,
							comparisonOperator == ComparisonOperator.NOT_EQUAL
									|| comparisonOperator == ComparisonOperator.DISTINCT_FROM,
							creationContext.getNodeBuilder()
					);
				}
				else if ( right instanceof SqmLiteralNull<?> ) {
					return new SqmNullnessPredicate(
							left,
							comparisonOperator == ComparisonOperator.NOT_EQUAL
									|| comparisonOperator == ComparisonOperator.DISTINCT_FROM,
							creationContext.getNodeBuilder()
					);
				}
				break;
			}
			default: {
				left = (SqmExpression<?>) leftExpressionContext.accept( this );
				right = (SqmExpression<?>) rightExpressionContext.accept( this );
				break;
			}
		}
		return new SqmComparisonPredicate(
				left,
				comparisonOperator,
				right,
				creationContext.getNodeBuilder()
		);
	}

	private SqmExpression<?> resolveEnumShorthandLiteral(HqlParser.ExpressionContext expressionContext, Map<Class<?>, Enum<?>> possibleEnumValues, Class<?> enumType) {
		final Enum<?> enumValue;
		if ( possibleEnumValues != null && ( enumValue = possibleEnumValues.get( enumType ) ) != null ) {
			DotIdentifierConsumer dotIdentifierConsumer = dotIdentifierConsumerStack.getCurrent();
			dotIdentifierConsumer.consumeIdentifier( enumValue.getClass().getCanonicalName(), true, false );
			dotIdentifierConsumer.consumeIdentifier( enumValue.name(), false, true );
			return (SqmExpression<?>) dotIdentifierConsumerStack.getCurrent().getConsumedPart();
		}
		else {
			return (SqmExpression<?>) expressionContext.accept( this );
		}
	}

	private Map<Class<?>, Enum<?>> getPossibleEnumValues(HqlParser.ExpressionContext expressionContext) {
		ParseTree ctx;
		// Traverse the expression structure according to the grammar
		if ( expressionContext instanceof HqlParser.CollateExpressionContext && expressionContext.getChildCount() == 1 ) {
			ctx = expressionContext.getChild( 0 );

			while ( ctx instanceof HqlParser.PrimaryExpressionContext && ctx.getChildCount() == 1 ) {
				ctx = ctx.getChild( 0 );
			}

			if ( ctx instanceof HqlParser.PathContext && ctx.getChildCount() == 1 ) {
				ctx = ctx.getChild( 0 );

				if ( ctx instanceof HqlParser.GeneralPathFragmentContext && ctx.getChildCount() == 1 ) {
					ctx = ctx.getChild( 0 );

					if ( ctx instanceof HqlParser.DotIdentifierSequenceContext ) {
						return creationContext.getJpaMetamodel().getAllowedEnumLiteralTexts().get( ctx.getText() );
					}
				}
			}
		}

		return null;
	}

	@Override
	public SqmPredicate visitLikePredicate(HqlParser.LikePredicateContext ctx) {
		final boolean negated = ( (TerminalNode) ctx.getChild( 1 ) ).getSymbol().getType() == HqlParser.NOT;
		final int startIndex = negated ? 3 : 2;
		if ( ctx.getChildCount() == startIndex + 2 ) {
			return new SqmLikePredicate(
					(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
					(SqmExpression<?>) ctx.getChild( startIndex ).accept( this ),
					(SqmExpression<?>) ctx.getChild( startIndex + 1 ).getChild( 1 ).accept( this ),
					negated,
					creationContext.getNodeBuilder()
			);
		}
		else {
			return new SqmLikePredicate(
					(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
					(SqmExpression<?>) ctx.getChild( startIndex ).accept( this ),
					negated,
					creationContext.getNodeBuilder()
			);
		}
	}

	@Override
	public SqmPredicate visitMemberOfPredicate(HqlParser.MemberOfPredicateContext ctx) {
		final boolean negated = ctx.getChildCount() == 5;
		final SqmPath<?> sqmPluralPath = consumeDomainPath(
				(HqlParser.PathContext) ctx.getChild( ctx.getChildCount() - 1 )
		);

		if ( sqmPluralPath.getReferencedPathSource() instanceof PluralPersistentAttribute ) {
			return new SqmMemberOfPredicate(
					(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
					sqmPluralPath,
					negated,
					creationContext.getNodeBuilder()
			);
		}
		else {
			throw new SemanticException( "Path argument to MEMBER OF must be a plural attribute" );
		}
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public SqmPredicate visitInPredicate(HqlParser.InPredicateContext ctx) {
		final boolean negated = ctx.getChildCount() == 4;
		final SqmExpression<?> testExpression = (SqmExpression<?>) ctx.getChild( 0 ).accept( this );
		final HqlParser.InListContext inListContext = (HqlParser.InListContext) ctx.getChild( ctx.getChildCount() - 1 );
		if ( inListContext instanceof HqlParser.ExplicitTupleInListContext ) {
			final HqlParser.ExplicitTupleInListContext tupleExpressionListContext = (HqlParser.ExplicitTupleInListContext) inListContext;
			final int size = tupleExpressionListContext.getChildCount();
			final int estimatedSize = size >> 1;
			final boolean isEnum = testExpression.getJavaType().isEnum();
			parameterDeclarationContextStack.push( () -> size == 3 );
			try {
				final List<SqmExpression<?>> listExpressions = new ArrayList<>( estimatedSize );
				for ( int i = 1; i < size; i++ ) {
					final ParseTree parseTree = tupleExpressionListContext.getChild( i );
					if ( parseTree instanceof HqlParser.ExpressionContext ) {
						final HqlParser.ExpressionContext expressionContext = (HqlParser.ExpressionContext) parseTree;
						final Map<Class<?>, Enum<?>> possibleEnumValues;
						if ( isEnum && ( possibleEnumValues = getPossibleEnumValues( expressionContext ) ) != null ) {
							listExpressions.add(
									resolveEnumShorthandLiteral(
											expressionContext,
											possibleEnumValues,
											testExpression.getJavaType()
									)
							);
						}
						else {
							listExpressions.add( (SqmExpression<?>) expressionContext.accept( this ) );
						}
					}
				}

				return new SqmInListPredicate(
						testExpression,
						listExpressions,
						negated,
						creationContext.getNodeBuilder()
				);
			}
			finally {
				parameterDeclarationContextStack.pop();
			}
		}
		else if ( inListContext instanceof HqlParser.ParamInListContext ) {
			final HqlParser.ParamInListContext tupleExpressionListContext = (HqlParser.ParamInListContext) inListContext;
			parameterDeclarationContextStack.push( () -> true );
			try {
				return new SqmInListPredicate(
						testExpression,
						Collections.singletonList( tupleExpressionListContext.getChild( 0 ).accept( this ) ),
						negated,
						creationContext.getNodeBuilder()
				);
			}
			finally {
				parameterDeclarationContextStack.pop();
			}
		}
		else if ( inListContext instanceof HqlParser.SubQueryInListContext ) {
			final HqlParser.SubQueryInListContext subQueryOrParamInListContext = (HqlParser.SubQueryInListContext) inListContext;
			return new SqmInSubQueryPredicate(
					testExpression,
					visitSubQuery( (HqlParser.SubQueryContext) subQueryOrParamInListContext.getChild( 1 ) ),
					negated,
					creationContext.getNodeBuilder()
			);
		}
		else {
			// todo : handle PersistentCollectionReferenceInList labeled branch

			throw new ParsingException( "Unexpected IN predicate type [" + ctx.getClass().getSimpleName() + "] : " + ctx.getText() );
		}
	}

	@Override
	public SqmPredicate visitExistsPredicate(HqlParser.ExistsPredicateContext ctx) {
		final SqmExpression<?> expression = (SqmExpression<?>) ctx.getChild( 1 ).accept( this );
		return new SqmExistsPredicate( expression, creationContext.getNodeBuilder() );
	}

	@Override
	public SqmPredicate visitBooleanExpressionPredicate(HqlParser.BooleanExpressionPredicateContext ctx) {
		final SqmExpression expression = (SqmExpression) ctx.expression().accept( this );
		if ( expression.getJavaType() != Boolean.class ) {
			throw new SemanticException( "Non-boolean expression used in predicate context: " + ctx.getText() );
		}
		return new SqmBooleanExpressionPredicate( expression, creationContext.getNodeBuilder() );
	}

	@Override
	public Object visitEntityTypeExpression(HqlParser.EntityTypeExpressionContext ctx) {
		final ParseTree pathOrParameter = ctx.getChild( 0 ).getChild( 2 );
		// can be one of 2 forms:
		//		1) TYPE( some.path )
		//		2) TYPE( :someParam )
		if ( pathOrParameter instanceof HqlParser.ParameterContext ) {
			// we have form (2)
			return new SqmParameterizedEntityType<>(
					(SqmParameter<?>) pathOrParameter.accept( this ),
					creationContext.getNodeBuilder()
			);
		}
		else if ( pathOrParameter instanceof HqlParser.PathContext ) {
			// we have form (1)
			return ( (SqmPath<?>) pathOrParameter.accept( this ) ).type();
		}

		throw new ParsingException( "Could not interpret grammar context as 'entity type' expression : " + ctx.getText() );
	}

	@Override
	public SqmExpression<?> visitEntityIdExpression(HqlParser.EntityIdExpressionContext ctx) {
		return visitEntityIdReference( (HqlParser.EntityIdReferenceContext) ctx.getChild( 0 ) );
	}

	@Override
	public SqmPath<?> visitEntityIdReference(HqlParser.EntityIdReferenceContext ctx) {
		final SqmPath<?> sqmPath = consumeDomainPath( (HqlParser.PathContext) ctx.getChild( 2 ) );
		final DomainType<?> sqmPathType = sqmPath.getReferencedPathSource().getSqmPathType();

		if ( sqmPathType instanceof IdentifiableDomainType<?> ) {
			//noinspection unchecked
			final SqmPath<?> idPath = ( (IdentifiableDomainType<?>) sqmPathType ).getIdentifierDescriptor()
					.createSqmPath( sqmPath );

			if ( ctx.getChildCount() != 5 ) {
				return idPath;
			}
			final HqlParser.PathContinuationContext pathContinuationContext = (HqlParser.PathContinuationContext) ctx.getChild( 4 );

			throw new NotYetImplementedFor6Exception( "Path continuation from `id()` reference not yet implemented" );
		}

		throw new SemanticException( "Path does not reference an identifiable-type : " + sqmPath.getNavigablePath().getFullPath() );
	}

	@Override
	public SqmExpression<?> visitEntityVersionExpression(HqlParser.EntityVersionExpressionContext ctx) {
		return visitEntityVersionReference( (HqlParser.EntityVersionReferenceContext) ctx.getChild( 0 ) );
	}

	@Override
	public SqmPath<?> visitEntityVersionReference(HqlParser.EntityVersionReferenceContext ctx) {
		final SqmPath<?> sqmPath = consumeDomainPath( (HqlParser.PathContext) ctx.getChild( 2 ) );
		final DomainType<?> sqmPathType = sqmPath.getReferencedPathSource().getSqmPathType();

		if ( sqmPathType instanceof IdentifiableDomainType<?> ) {
			final IdentifiableDomainType<?> identifiableType = (IdentifiableDomainType<?>) sqmPathType;
			final SingularPersistentAttribute<?, ?> versionAttribute = identifiableType.findVersionAttribute();
			if ( versionAttribute == null ) {
				throw new SemanticException(
						"`" + sqmPath.getNavigablePath().getFullPath() + "` resolved to an identifiable-type (`" +
								identifiableType.getTypeName() + "`) which does not define a version"
				);
			}

			return versionAttribute.createSqmPath( sqmPath );
		}

		throw new SemanticException( "Path does not reference an identifiable-type : " + sqmPath.getNavigablePath().getFullPath() );
	}

	@Override
	public SqmPath<?> visitEntityNaturalIdExpression(HqlParser.EntityNaturalIdExpressionContext ctx) {
		return visitEntityNaturalIdReference( (HqlParser.EntityNaturalIdReferenceContext) ctx.getChild( 0 ) );
	}

	@Override
	public SqmPath<?> visitEntityNaturalIdReference(HqlParser.EntityNaturalIdReferenceContext ctx) {
		throw new NotYetImplementedFor6Exception( "Support for HQL natural-id references not yet implemented" );
	}

	@Override
	public SqmMapEntryReference<?, ?> visitMapEntrySelection(HqlParser.MapEntrySelectionContext ctx) {
		return new SqmMapEntryReference<>(
				consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 2 ) ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitConcatenationExpression(HqlParser.ConcatenationExpressionContext ctx) {
		if ( ctx.getChildCount() != 3 ) {
			throw new ParsingException( "Expecting 2 operands to the concat operator" );
		}
		return getFunctionDescriptor( "concat" ).generateSqmExpression(
				asList(
						(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
						(SqmExpression<?>) ctx.getChild( 2 ).accept( this )
				),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitSignOperator(HqlParser.SignOperatorContext ctx) {
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.PLUS:
				return UnaryArithmeticOperator.UNARY_PLUS;
			case HqlParser.MINUS:
				return UnaryArithmeticOperator.UNARY_MINUS;
			default:
				throw new QueryException( "missing operator" );
		}
	}

	@Override
	public Object visitAdditiveOperator(HqlParser.AdditiveOperatorContext ctx) {
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.PLUS:
				return BinaryArithmeticOperator.ADD;
			case HqlParser.MINUS:
				return BinaryArithmeticOperator.SUBTRACT;
			default:
				throw new QueryException( "missing operator" );
		}
	}

	@Override
	public Object visitMultiplicativeOperator(HqlParser.MultiplicativeOperatorContext ctx) {
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.ASTERISK:
				return BinaryArithmeticOperator.MULTIPLY;
			case HqlParser.SLASH:
				return BinaryArithmeticOperator.DIVIDE;
			case HqlParser.PERCENT_OP:
				return BinaryArithmeticOperator.MODULO;
			default:
				throw new QueryException( "missing operator" );
		}
	}

	@Override
	public Object visitAdditionExpression(HqlParser.AdditionExpressionContext ctx) {
		if ( ctx.getChildCount() != 3 ) {
			throw new ParsingException( "Expecting 2 operands to the additive operator" );
		}

		return new SqmBinaryArithmetic<>(
				(BinaryArithmeticOperator) ctx.getChild( 1 ).accept( this ),
				(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
				(SqmExpression<?>) ctx.getChild( 2 ).accept( this ),
				creationContext.getJpaMetamodel(),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public Object visitMultiplicationExpression(HqlParser.MultiplicationExpressionContext ctx) {
		if ( ctx.getChildCount() != 3 ) {
			throw new ParsingException( "Expecting 2 operands to the multiplicative operator" );
		}

		final SqmExpression<?> left = (SqmExpression<?>) ctx.getChild( 0 ).accept( this );
		final SqmExpression<?> right = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final BinaryArithmeticOperator operator = (BinaryArithmeticOperator) ctx.getChild( 1 ).accept( this );

		if ( operator == BinaryArithmeticOperator.MODULO ) {
			return getFunctionDescriptor("mod").generateSqmExpression(
					asList( left, right ),
					(AllowableFunctionReturnType<?>) left.getNodeType(),
					creationContext.getQueryEngine(),
					creationContext.getJpaMetamodel().getTypeConfiguration()
			);
		}
		else {
			return new SqmBinaryArithmetic<>(
					operator,
					left,
					right,
					creationContext.getJpaMetamodel(),
					creationContext.getNodeBuilder()
			);
		}
	}

	@Override
	public Object visitToDurationExpression(HqlParser.ToDurationExpressionContext ctx) {
		return new SqmToDuration<>(
				(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
				toDurationUnit( (SqmExtractUnit<?>) ctx.getChild( 1 ).accept( this ) ),
				resolveExpressableTypeBasic( Duration.class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmDurationUnit<Long> toDurationUnit(SqmExtractUnit<?> extractUnit) {
		return new SqmDurationUnit<>(
				extractUnit.getUnit(),
				resolveExpressableTypeBasic( Long.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public Object visitFromDurationExpression(HqlParser.FromDurationExpressionContext ctx) {
		return new SqmByUnit(
				toDurationUnit( (SqmExtractUnit<?>) ctx.getChild( 2 ).accept( this ) ),
				(SqmExpression<?>) ctx.getChild( 0 ).accept( this ),
				resolveExpressableTypeBasic( Long.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmUnaryOperation<?> visitUnaryExpression(HqlParser.UnaryExpressionContext ctx) {
		return new SqmUnaryOperation<>(
				(UnaryArithmeticOperator) ctx.getChild( 0 ).accept( this ),
				(SqmExpression<?>) ctx.getChild( 1 ).accept( this )
		);
	}

	@Override
	public Object visitGroupedExpression(HqlParser.GroupedExpressionContext ctx) {
		return ctx.getChild( 1 ).accept( this );
	}

	@Override
	public Object visitCollateExpression(HqlParser.CollateExpressionContext ctx) {
		SqmExpression<?> expression = (SqmExpression<?>) ctx.getChild( 0 ).accept( this );
		if ( ctx.getChildCount() == 1 ) {
			return expression;
		}
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation(
					StrictJpaComplianceViolation.Type.COLLATIONS
			);
		}
		return new SqmCollate<>( expression, ctx.getChild( 1 ).getChild( 1 ).getText() );
	}

	@Override
	public Object visitTupleExpression(HqlParser.TupleExpressionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation(
					StrictJpaComplianceViolation.Type.TUPLES
			);
		}
		final List<SqmExpression<?>> expressions = visitExpressions( ctx );
		return new SqmTuple<>( expressions, creationContext.getNodeBuilder() );
	}

	private List<SqmExpression<?>> visitExpressions(ParserRuleContext parentContext) {
		final int size = parentContext.getChildCount();
		// Shift 1 bit instead of division by 2
		final int estimateExpressionsCount = ( size >> 1 ) - 1;
		final List<SqmExpression<?>> expressions = new ArrayList<>( estimateExpressionsCount );
		for ( int i = 0; i < size; i++ ) {
			final ParseTree parseTree = parentContext.getChild( i );
			if ( parseTree instanceof HqlParser.ExpressionContext ) {
				expressions.add( (SqmExpression<?>) parseTree.accept( this ) );
			}
		}
		return expressions;
	}

	@Override
	public Object visitCaseExpression(HqlParser.CaseExpressionContext ctx) {
		return ctx.getChild( 0 ).accept( this );
	}

	@Override
	public SqmCaseSimple<?, ?> visitSimpleCaseList(HqlParser.SimpleCaseListContext ctx) {
		final int size = ctx.getChildCount();
		//noinspection unchecked
		final SqmCaseSimple<Object, Object> caseExpression = new SqmCaseSimple<>(
				(SqmExpression<Object>) ctx.getChild( 1 ).accept( this ),
				null,
				size - 3,
				creationContext.getNodeBuilder()
		);

		for ( int i = 2; i < size; i++ ) {
			final ParseTree parseTree = ctx.getChild( i );
			if ( parseTree instanceof HqlParser.SimpleCaseWhenContext ) {
				//noinspection unchecked
				caseExpression.when(
						(SqmExpression<Object>) parseTree.getChild( 1 ).accept( this ),
						(SqmExpression<Object>) parseTree.getChild( 3 ).accept( this )
				);
			}
		}

		final ParseTree lastChild = ctx.getChild( ctx.getChildCount() - 2 );
		if ( lastChild instanceof HqlParser.CaseOtherwiseContext ) {
			//noinspection unchecked
			caseExpression.otherwise( (SqmExpression<Object>) lastChild.getChild( 1 ).accept( this ) );
		}

		return caseExpression;
	}

	@Override
	public SqmCaseSearched<?> visitSearchedCaseList(HqlParser.SearchedCaseListContext ctx) {
		final int size = ctx.getChildCount();
		final SqmCaseSearched<Object> caseExpression = new SqmCaseSearched<>(
				null,
				size - 2,
				creationContext.getNodeBuilder()
		);

		for ( int i = 1; i < size; i++ ) {
			final ParseTree parseTree = ctx.getChild( i );
			if ( parseTree instanceof HqlParser.SearchedCaseWhenContext ) {
				//noinspection unchecked
				caseExpression.when(
						(SqmPredicate) parseTree.getChild( 1 ).accept( this ),
						(SqmExpression<Object>) parseTree.getChild( 3 ).accept( this )
				);
			}
		}

		final ParseTree lastChild = ctx.getChild( ctx.getChildCount() - 2 );
		if ( lastChild instanceof HqlParser.CaseOtherwiseContext ) {
			//noinspection unchecked
			caseExpression.otherwise( (SqmExpression<Object>) lastChild.getChild( 1 ).accept( this ) );
		}

		return caseExpression;
	}

	@Override
	public SqmExpression<?> visitCurrentDateFunction(HqlParser.CurrentDateFunctionContext ctx) {
		return getFunctionDescriptor("current_date")
				.generateSqmExpression(
						resolveExpressableTypeBasic( Date.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitCurrentTimeFunction(HqlParser.CurrentTimeFunctionContext ctx) {
		return getFunctionDescriptor("current_time")
				.generateSqmExpression(
						resolveExpressableTypeBasic( Time.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitCurrentTimestampFunction(HqlParser.CurrentTimestampFunctionContext ctx) {
		return getFunctionDescriptor("current_timestamp")
				.generateSqmExpression(
						resolveExpressableTypeBasic( Timestamp.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitInstantFunction(HqlParser.InstantFunctionContext ctx) {
		return getFunctionDescriptor("instant")
				.generateSqmExpression(
						resolveExpressableTypeBasic( Instant.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitLocalDateFunction(HqlParser.LocalDateFunctionContext ctx) {
		return getFunctionDescriptor("local_date")
				.generateSqmExpression(
						resolveExpressableTypeBasic( LocalDate.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitLocalTimeFunction(HqlParser.LocalTimeFunctionContext ctx) {
		return getFunctionDescriptor("local_time")
				.generateSqmExpression(
						resolveExpressableTypeBasic( LocalTime.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitLocalDateTimeFunction(HqlParser.LocalDateTimeFunctionContext ctx) {
		return getFunctionDescriptor("local_datetime")
				.generateSqmExpression(
						resolveExpressableTypeBasic( LocalDateTime.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitOffsetDateTimeFunction(HqlParser.OffsetDateTimeFunctionContext ctx) {
		return getFunctionDescriptor("offset_datetime")
				.generateSqmExpression(
						resolveExpressableTypeBasic( OffsetDateTime.class ),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public Object visitLeastFunction(HqlParser.LeastFunctionContext ctx) {
		final List<SqmExpression<?>> arguments = visitExpressions( ctx );
		return getFunctionDescriptor("least")
				.generateSqmExpression(
						arguments,
						(AllowableFunctionReturnType<?>) arguments.get( 0 ).getNodeType(),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public Object visitGreatestFunction(HqlParser.GreatestFunctionContext ctx) {
		final List<SqmExpression<?>> arguments = visitExpressions( ctx );
		return getFunctionDescriptor("greatest")
				.generateSqmExpression(
						arguments,
						(AllowableFunctionReturnType<?>) arguments.get( 0 ).getNodeType(),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitCoalesceFunction(HqlParser.CoalesceFunctionContext ctx) {
		final List<SqmExpression<?>> arguments = visitExpressions( ctx );
		return getFunctionDescriptor("coalesce")
				.generateSqmExpression(
						arguments,
						(AllowableFunctionReturnType<?>) arguments.get( 0 ).getNodeType(),
						creationContext.getQueryEngine(),
						creationContext.getJpaMetamodel().getTypeConfiguration()
				);
	}

	@Override
	public SqmExpression<?> visitNullifFunction(HqlParser.NullifFunctionContext ctx) {
		final SqmExpression<?> arg1 = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> arg2 = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("nullif").generateSqmExpression(
				asList( arg1, arg2 ),
				(AllowableFunctionReturnType<?>) arg1.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitIfnullFunction(HqlParser.IfnullFunctionContext ctx) {
		final SqmExpression<?> arg1 = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> arg2 = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("ifnull").generateSqmExpression(
				asList( arg1, arg2 ),
				(AllowableFunctionReturnType<?>) arg1.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitLiteralExpression(HqlParser.LiteralExpressionContext ctx) {
		return (SqmExpression<?>) ctx.getChild( 0 ).accept( this );
	}

	@Override
	public Object visitBinaryLiteral(HqlParser.BinaryLiteralContext ctx) {
		final TerminalNode firstNode = (TerminalNode) ctx.getChild( 0 );
		if ( firstNode.getSymbol().getType() == HqlParser.BINARY_LITERAL ) {
			return binaryLiteral( firstNode.getText() );
		}
		else {
			final StringBuilder text = new StringBuilder( "x'" );
			final int size = ctx.getChildCount();
			for ( int i = 0; i < size; i++ ) {
				final TerminalNode hex = (TerminalNode) ctx.getChild( i );
				if ( hex.getSymbol().getType() == HqlParser.HEX_LITERAL ) {
					final String hexText = hex.getText();
					if ( hexText.length() != 4 ) {
						throw new LiteralNumberFormatException( "not a byte: " + hexText );
					}
					text.append( hexText, 2, hexText.length() );
				}
			}
			return binaryLiteral( text.append( "'" ).toString() );
		}
	}

	@Override
	public Object visitGeneralizedLiteral(HqlParser.GeneralizedLiteralContext ctx) {
		throw new NotYetImplementedFor6Exception( getClass() );
	}

	@Override
	public SqmExpression<?> visitTerminal(TerminalNode node) {
		if ( node.getSymbol().getType() == HqlLexer.EOF ) {
			return null;
		}
		switch ( node.getSymbol().getType() ) {
			case HqlParser.STRING_LITERAL:
				return stringLiteral( node.getText() );
			case HqlParser.INTEGER_LITERAL:
				return integerLiteral( node.getText() );
			case HqlParser.LONG_LITERAL:
				return longLiteral( node.getText() );
			case HqlParser.BIG_INTEGER_LITERAL:
				return bigIntegerLiteral( node.getText() );
			case HqlParser.HEX_LITERAL:
				return hexLiteral( node.getText() );
			case HqlParser.FLOAT_LITERAL:
				return floatLiteral( node.getText() );
			case HqlParser.DOUBLE_LITERAL:
				return doubleLiteral( node.getText() );
			case HqlParser.BIG_DECIMAL_LITERAL:
				return bigDecimalLiteral( node.getText() );
			case HqlParser.FALSE:
				return booleanLiteral( false );
			case HqlParser.TRUE:
				return booleanLiteral( true );
			case HqlParser.NULL:
				return new SqmLiteralNull<>( creationContext.getQueryEngine().getCriteriaBuilder() );
			case HqlParser.BINARY_LITERAL:
				return binaryLiteral( node.getText() );
			default:
				throw new ParsingException("Unexpected terminal node [" + node.getText() + "]");
		}
	}

	@Override
	public Object visitDateTimeLiteral(HqlParser.DateTimeLiteralContext ctx) {
		return ctx.getChild( 1 ).accept( this );
	}

	@Override
	public Object visitDateLiteral(HqlParser.DateLiteralContext ctx) {
		return ctx.getChild( 1 ).accept( this );
	}

	@Override
	public Object visitTimeLiteral(HqlParser.TimeLiteralContext ctx) {
		return ctx.getChild( 1 ).accept( this );
	}

	@Override
	public Object visitJdbcTimestampLiteral(HqlParser.JdbcTimestampLiteralContext ctx) {
		final ParseTree parseTree = ctx.getChild( 1 );
		if ( parseTree instanceof HqlParser.DateTimeContext ) {
			return parseTree.accept( this );
		}
		else {
			return sqlTimestampLiteralFrom( parseTree.getText() );
		}
	}

	@Override
	public Object visitJdbcDateLiteral(HqlParser.JdbcDateLiteralContext ctx) {
		final ParseTree parseTree = ctx.getChild( 1 );
		if ( parseTree instanceof HqlParser.DateContext ) {
			return parseTree.accept( this );
		}
		else {
			return sqlDateLiteralFrom( parseTree.getText() );
		}
	}

	@Override
	public Object visitJdbcTimeLiteral(HqlParser.JdbcTimeLiteralContext ctx) {
		final ParseTree parseTree = ctx.getChild( 1 );
		if ( parseTree instanceof HqlParser.TimeContext ) {
			return parseTree.accept( this );
		}
		else {
			return sqlTimeLiteralFrom( parseTree.getText() );
		}
	}

	@Override
	public Object visitDateTime(HqlParser.DateTimeContext ctx) {
		final ParseTree parseTree = ctx.getChild( 2 );
		if ( parseTree instanceof HqlParser.ZoneIdContext || parseTree == null ) {
			return dateTimeLiteralFrom(
					(HqlParser.DateContext) ctx.getChild( 0 ),
					(HqlParser.TimeContext) ctx.getChild( 1 ),
					(HqlParser.ZoneIdContext) parseTree
			);
		}
		else {
			return offsetDatetimeLiteralFrom(
					(HqlParser.DateContext) ctx.getChild( 0 ),
					(HqlParser.TimeContext) ctx.getChild( 1 ),
					(HqlParser.OffsetContext) parseTree
			);
		}
	}

	private SqmLiteral<?> dateTimeLiteralFrom(
			HqlParser.DateContext date,
			HqlParser.TimeContext time,
			HqlParser.ZoneIdContext timezone) {
		if ( timezone == null ) {
			return new SqmLiteral<>(
					LocalDateTime.of( localDate( date ), localTime( time ) ),
					resolveExpressableTypeBasic( LocalDateTime.class ),
					creationContext.getNodeBuilder()
			);
		}
		else {
			final ZoneId zoneId = visitZoneId( timezone );
			return new SqmLiteral<>(
					ZonedDateTime.of( localDate( date ), localTime( time ), zoneId ),
					resolveExpressableTypeBasic( ZonedDateTime.class ),
					creationContext.getNodeBuilder()
			);
		}
	}

	@Override
	public ZoneId visitZoneId(HqlParser.ZoneIdContext ctx) {
		final String timezoneText = ctx.getText();
		final String timezoneFullName = ZoneId.SHORT_IDS.get( timezoneText );
		if ( timezoneFullName == null ) {
			return ZoneId.of( timezoneText );
		}
		else {
			return ZoneId.of( timezoneFullName );
		}
	}

	private SqmLiteral<?> offsetDatetimeLiteralFrom(
			HqlParser.DateContext date,
			HqlParser.TimeContext time,
			HqlParser.OffsetContext offset) {
		return new SqmLiteral<>(
				OffsetDateTime.of( localDate( date ), localTime( time ), zoneOffset( offset ) ),
				resolveExpressableTypeBasic( OffsetDateTime.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public Object visitDate(HqlParser.DateContext ctx) {
		return new SqmLiteral<>(
				localDate( ctx ),
				resolveExpressableTypeBasic( LocalDate.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public Object visitTime(HqlParser.TimeContext ctx) {
		return new SqmLiteral<>(
				localTime( ctx ),
				resolveExpressableTypeBasic( LocalTime.class ),
				creationContext.getNodeBuilder()
		);
	}

	private static LocalTime localTime(HqlParser.TimeContext ctx) {
		final int hour = Integer.parseInt( ctx.getChild( 0 ).getText() );
		final int minute = Integer.parseInt( ctx.getChild( 2 ).getText() );
		if ( ctx.getChildCount() == 5 ) {
			final String secondText = ctx.getChild( 4 ).getText();
			final int index = secondText.indexOf( '.');
			if ( index < 0 ) {
				return LocalTime.of(
						hour,
						minute,
						Integer.parseInt( secondText )
				);
			}
			else {
				return LocalTime.of(
						hour,
						minute,
						Integer.parseInt( secondText.substring( 0, index ) ),
						Integer.parseInt( secondText.substring( index + 1 ) )
				);
			}
		}
		else {
			return LocalTime.of( hour, minute );
		}
	}

	private static LocalDate localDate(HqlParser.DateContext ctx) {
		return LocalDate.of(
				Integer.parseInt( ctx.getChild( 0 ).getText() ),
				Integer.parseInt( ctx.getChild( 2 ).getText() ),
				Integer.parseInt( ctx.getChild( 4 ).getText() )
		);
	}

	private static ZoneOffset zoneOffset(HqlParser.OffsetContext offset) {
		final int factor = ( (TerminalNode) offset.getChild( 0 ) ).getSymbol().getType() == PLUS ? 1 : -1;
		final int hour = factor * Integer.parseInt( offset.getChild( 1 ).getText() );
		if ( offset.getChildCount() == 2 ) {
			return ZoneOffset.ofHours( hour );
		}
		return ZoneOffset.ofHoursMinutes(
				hour,
				factor * Integer.parseInt( offset.getChild( 3 ).getText() )
		);
	}

//	private SqmLiteral<OffsetDateTime> offsetDatetimeLiteralFrom(String literalText) {
//		TemporalAccessor parsed = OFFSET_DATE_TIME.parse( literalText );
//		return new SqmLiteral<>(
//				OffsetDateTime.from( parsed ),
//				resolveExpressableTypeBasic( OffsetDateTime.class ),
//				creationContext.getNodeBuilder()
//		);
//	}
//
//	private SqmLiteral<?> dateTimeLiteralFrom(String literalText) {
//		//TO DO: return an OffsetDateTime when appropriate?
//		TemporalAccessor parsed = DATE_TIME.parse( literalText );
//		try {
//			return new SqmLiteral<>(
//					ZonedDateTime.from( parsed ),
//					resolveExpressableTypeBasic( ZonedDateTime.class ),
//					creationContext.getNodeBuilder()
//			);
//		}
//		catch (DateTimeException dte) {
//			return new SqmLiteral<>(
//					LocalDateTime.from( parsed ),
//					resolveExpressableTypeBasic( LocalDateTime.class ),
//					creationContext.getNodeBuilder()
//			);
//		}
//	}
//
//	private SqmLiteral<LocalDate> localDateLiteralFrom(String literalText) {
//		return new SqmLiteral<>(
//				LocalDate.from( ISO_LOCAL_DATE.parse( literalText ) ),
//				resolveExpressableTypeBasic( LocalDate.class ),
//				creationContext.getNodeBuilder()
//		);
//	}
//
//	private SqmLiteral<LocalTime> localTimeLiteralFrom(String literalText) {
//		return new SqmLiteral<>(
//				LocalTime.from( ISO_LOCAL_TIME.parse( literalText ) ),
//				resolveExpressableTypeBasic( LocalTime.class ),
//				creationContext.getNodeBuilder()
//		);
//	}

	private SqmLiteral<?> sqlTimestampLiteralFrom(String literalText) {
		final TemporalAccessor parsed = DATE_TIME.parse( literalText );
		try {
			final ZonedDateTime zonedDateTime = ZonedDateTime.from( parsed );
			final Calendar literal = GregorianCalendar.from( zonedDateTime );
			return new SqmLiteral<>(
					literal,
					resolveExpressableTypeBasic( Calendar.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (DateTimeException dte) {
			final LocalDateTime localDateTime = LocalDateTime.from( parsed );
			final Timestamp literal = Timestamp.valueOf( localDateTime );
			return new SqmLiteral<>(
					literal,
					resolveExpressableTypeBasic( Timestamp.class ),
					creationContext.getNodeBuilder()
			);
		}
	}

	private SqmLiteral<Date> sqlDateLiteralFrom(String literalText) {
		final LocalDate localDate = LocalDate.from( ISO_LOCAL_DATE.parse( literalText ) );
		final Date literal = Date.valueOf( localDate );
		return new SqmLiteral<>(
				literal,
				resolveExpressableTypeBasic( Date.class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmLiteral<Time> sqlTimeLiteralFrom(String literalText) {
		final LocalTime localTime = LocalTime.from( ISO_LOCAL_TIME.parse( literalText ) );
		final Time literal = Time.valueOf( localTime );
		return new SqmLiteral<>(
				literal,
				resolveExpressableTypeBasic( Time.class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmLiteral<Boolean> booleanLiteral(boolean value) {
		return new SqmLiteral<>(
				value,
				resolveExpressableTypeBasic( Boolean.class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmLiteral<String> stringLiteral(String text) {
		return new SqmLiteral<>(
				text,
				resolveExpressableTypeBasic( String.class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmLiteral<byte[]> binaryLiteral(String text) {
		return new SqmLiteral<>(
				StandardBasicTypes.BINARY.fromStringValue( text.substring( 2, text.length()-1 ) ),
				resolveExpressableTypeBasic( byte[].class ),
				creationContext.getNodeBuilder()
		);
	}

	private SqmLiteral<Integer> integerLiteral(String text) {
		try {
			final Integer value = Integer.valueOf( text );
			return new SqmLiteral<>(
					value,
					resolveExpressableTypeBasic( Integer.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + text + "] to Integer",
					e
			);
		}
	}

	private SqmLiteral<Long> longLiteral(String text) {
		final String originalText = text;
		try {
			if ( text.endsWith( "l" ) || text.endsWith( "L" ) ) {
				text = text.substring( 0, text.length() - 1 );
			}
			final Long value = Long.valueOf( text );
			return new SqmLiteral<>(
					value,
					resolveExpressableTypeBasic( Long.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + originalText + "] to Long",
					e
			);
		}
	}

	private SqmLiteral<? extends Number> hexLiteral(String text) {
		final String originalText = text;
		text = text.substring( 2 );
		try {
			final Number value;
			final BasicDomainType<? extends Number> type;
			if ( text.endsWith( "l" ) || text.endsWith( "L" ) ) {
				text = text.substring( 0, text.length() - 1 );
				value = Long.parseUnsignedLong( text, 16 );
				type = resolveExpressableTypeBasic( Long.class );
			}
			else {
				value = Integer.parseUnsignedInt( text, 16 );
				type = resolveExpressableTypeBasic( Integer.class );
			}
			//noinspection unchecked
			return new SqmLiteral<>(
					value,
					(SqmExpressable<Number>) type,
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + originalText + "]",
					e
			);
		}
	}

	private SqmLiteral<BigInteger> bigIntegerLiteral(String text) {
		final String originalText = text;
		try {
			if ( text.endsWith( "bi" ) || text.endsWith( "BI" ) ) {
				text = text.substring( 0, text.length() - 2 );
			}
			return new SqmLiteral<>(
					new BigInteger( text ),
					resolveExpressableTypeBasic( BigInteger.class  ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + originalText + "] to BigInteger",
					e
			);
		}
	}

	private SqmLiteral<Float> floatLiteral(String text) {
		try {
			return new SqmLiteral<>(
					Float.valueOf( text ),
					resolveExpressableTypeBasic( Float.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + text + "] to Float",
					e
			);
		}
	}

	private SqmLiteral<Double> doubleLiteral(String text) {
		try {
			return new SqmLiteral<>(
					Double.valueOf( text ),
					resolveExpressableTypeBasic( Double.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + text + "] to Double",
					e
			);
		}
	}

	private SqmLiteral<BigDecimal> bigDecimalLiteral(String text) {
		final String originalText = text;
		try {
			if ( text.endsWith( "bd" ) || text.endsWith( "BD" ) ) {
				text = text.substring( 0, text.length() - 2 );
			}
			return new SqmLiteral<>(
					new BigDecimal( text ),
					resolveExpressableTypeBasic( BigDecimal.class ),
					creationContext.getNodeBuilder()
			);
		}
		catch (NumberFormatException e) {
			throw new LiteralNumberFormatException(
					"Unable to convert sqm literal [" + originalText + "] to BigDecimal",
					e
			);
		}
	}

	private <J> BasicDomainType<J> resolveExpressableTypeBasic(Class<J> javaType) {
		return creationContext.getJpaMetamodel().getTypeConfiguration().standardBasicTypeForJavaType( javaType );
	}

	@Override
	public Object visitParameterExpression(HqlParser.ParameterExpressionContext ctx) {
		return ctx.getChild( 0 ).accept( this );
	}

	@Override
	public SqmNamedParameter<?> visitNamedParameter(HqlParser.NamedParameterContext ctx) {
		final SqmNamedParameter<?> param = new SqmNamedParameter<>(
				ctx.getChild( 1 ).getText(),
				parameterDeclarationContextStack.getCurrent().isMultiValuedBindingAllowed(),
				creationContext.getNodeBuilder()
		);
		parameterCollector.addParameter( param );
		return param;
	}

	@Override
	public SqmPositionalParameter<?> visitPositionalParameter(HqlParser.PositionalParameterContext ctx) {
		if ( ctx.getChildCount() == 1 ) {
			throw new SemanticException( "Encountered positional parameter which did not declare position (? instead of, e.g., ?1)" );
		}
		final SqmPositionalParameter<?> param = new SqmPositionalParameter<>(
				Integer.parseInt( ctx.getChild( 1 ).getText() ),
				parameterDeclarationContextStack.getCurrent().isMultiValuedBindingAllowed(),
				creationContext.getNodeBuilder()
		);
		parameterCollector.addParameter( param );
		return param;
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Functions

	@Override
	public SqmExpression<?> visitJpaNonStandardFunction(HqlParser.JpaNonStandardFunctionContext ctx) {
		final String functionName = ctx.getChild( 2 ).getText().toLowerCase();
		final List<SqmTypedNode<?>> functionArguments;
		if ( ctx.getChildCount() > 4 ) {
			//noinspection unchecked
			functionArguments = (List<SqmTypedNode<?>>) ctx.getChild( 4 ).accept( this );
		}
		else {
			functionArguments = emptyList();
		}

		SqmFunctionDescriptor functionTemplate = getFunctionDescriptor( functionName );
		if (functionTemplate == null) {
			functionTemplate = new NamedSqmFunctionDescriptor(
					functionName,
					true,
					null,
					StandardFunctionReturnTypeResolvers.invariant( StandardBasicTypes.OBJECT_TYPE )
			);
		}
		return functionTemplate.generateSqmExpression(
				functionArguments,
				null,
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitNonStandardFunction(HqlParser.NonStandardFunctionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation(
					"Encountered non-compliant non-standard function call [" +
							ctx.nonStandardFunctionName() + "], but strict JPA " +
							"compliance was requested; use JPA's FUNCTION(functionName[,...]) " +
							"syntax name instead",
					StrictJpaComplianceViolation.Type.FUNCTION_CALL
			);
		}

		final String functionName = ctx.getChild( 0 ).getText().toLowerCase();
		//noinspection unchecked
		final List<SqmTypedNode<?>> functionArguments = ctx.getChildCount() == 3
				? emptyList()
				: (List<SqmTypedNode<?>>) ctx.getChild( 2 ).accept( this );

		SqmFunctionDescriptor functionTemplate = getFunctionDescriptor( functionName );
		if ( functionTemplate == null ) {
			functionTemplate = new NamedSqmFunctionDescriptor(
					functionName,
					true,
					null,
					StandardFunctionReturnTypeResolvers.invariant( StandardBasicTypes.OBJECT_TYPE )
			);
		}

		return functionTemplate.generateSqmExpression(
				functionArguments,
				null,
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public List<SqmTypedNode<?>> visitNonStandardFunctionArguments(HqlParser.NonStandardFunctionArgumentsContext ctx) {
		final int size = ctx.getChildCount();
		final int lastIndex = size - 1;
		// Shift 1 bit instead of division by 2
		final int estimateArgumentCount = size >> 1;
		final List<SqmTypedNode<?>> arguments = new ArrayList<>( estimateArgumentCount );
		int i = 0;

		final ParseTree firstChild = ctx.getChild( 0 );
		if ( firstChild instanceof HqlParser.DatetimeFieldContext ) {
			arguments.add( toDurationUnit( (SqmExtractUnit<?>) firstChild.accept( this ) ) );
			i += 2;
		}

		for ( ; i < size; i += 2 ) {
			// we handle the final argument differently...
			if ( i == lastIndex ) {
				arguments.add( visitFinalFunctionArgument( (HqlParser.ExpressionContext) ctx.getChild( i ) ) );
			}
			else {
				arguments.add( (SqmTypedNode<?>) ctx.getChild( i ).accept( this ) );
			}
		}

		return arguments;
	}

	private SqmExpression<?> visitFinalFunctionArgument(HqlParser.ExpressionContext expression) {
		// the final argument to a function may accept multi-value parameter (varargs),
		// 		but only if we are operating in non-strict JPA mode
		parameterDeclarationContextStack.push( () -> !creationOptions.useStrictJpaCompliance() );
		try {
			return (SqmExpression<?>) expression.accept( this );
		}
		finally {
			parameterDeclarationContextStack.pop();
		}
	}

	@Override
	public SqmExpression<?> visitCeilingFunction(HqlParser.CeilingFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("ceiling").generateSqmExpression(
				arg,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitFloorFunction(HqlParser.FloorFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("floor").generateSqmExpression(
				arg,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	private SqmFunctionDescriptor getFunctionDescriptor(String name) {
		return creationContext.getQueryEngine().getSqmFunctionRegistry().findFunctionDescriptor(name);
	}

	@Override
	public SqmExpression<?> visitAbsFunction(HqlParser.AbsFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("abs").generateSqmExpression(
				arg,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitSignFunction(HqlParser.SignFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("sign").generateSqmExpression(
				arg,
				resolveExpressableTypeBasic( Integer.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitModFunction(HqlParser.ModFunctionContext ctx) {
		final SqmExpression<?> dividend = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> divisor = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("mod").generateSqmExpression(
				asList( dividend, divisor ),
				(AllowableFunctionReturnType<?>) dividend.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitPowerFunction(HqlParser.PowerFunctionContext ctx) {
		final SqmExpression<?> base = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> power = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("power").generateSqmExpression(
				asList( base, power ),
				(AllowableFunctionReturnType<?>) base.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitTrigFunction(HqlParser.TrigFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor( ctx.getChild( 0 ).getText() ).generateSqmExpression(
				arg,
				resolveExpressableTypeBasic( Double.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitSqrtFunction(HqlParser.SqrtFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("sqrt").generateSqmExpression(
				arg,
				null,
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitRoundFunction(HqlParser.RoundFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> precision = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("round").generateSqmExpression(
				asList(arg, precision),
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitAtan2Function(HqlParser.Atan2FunctionContext ctx) {
		final SqmExpression<?> sin = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> cos = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("atan2").generateSqmExpression(
				asList(sin, cos),
				(AllowableFunctionReturnType<?>) sin.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitLnFunction(HqlParser.LnFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("ln").generateSqmExpression(
				arg,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);

	}

	@Override
	public SqmExpression<?> visitExpFunction(HqlParser.ExpFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("exp").generateSqmExpression(
				arg,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitDatetimeField(HqlParser.DatetimeFieldContext ctx) {
		final NodeBuilder nodeBuilder = creationContext.getNodeBuilder();
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.DAY:
				return new SqmExtractUnit<>(
						TemporalUnit.DAY,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.MONTH:
				return new SqmExtractUnit<>(
						TemporalUnit.MONTH,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.YEAR:
				return new SqmExtractUnit<>(
						TemporalUnit.YEAR,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.HOUR:
				return new SqmExtractUnit<>(
						TemporalUnit.HOUR,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.MINUTE:
				return new SqmExtractUnit<>(
						TemporalUnit.MINUTE,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.SECOND:
				return new SqmExtractUnit<>(
						TemporalUnit.SECOND,
						resolveExpressableTypeBasic( Float.class ),
						nodeBuilder
				);
			case HqlParser.NANOSECOND:
				return new SqmExtractUnit<>(
						NANOSECOND,
						resolveExpressableTypeBasic( Long.class ),
						nodeBuilder
				);
			case HqlParser.WEEK:
				return new SqmExtractUnit<>(
						TemporalUnit.WEEK,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			case HqlParser.QUARTER:
				return new SqmExtractUnit<>(
						TemporalUnit.QUARTER,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
		}
		throw new ParsingException("Unsupported datetime field [" + ctx.getText() + "]");
	}

	@Override
	public Object visitDayField(HqlParser.DayFieldContext ctx) {
		final NodeBuilder nodeBuilder = creationContext.getNodeBuilder();
		switch ( ( (TerminalNode) ctx.getChild( 2 ) ).getSymbol().getType() ) {
			case HqlParser.MONTH:
				return new SqmExtractUnit<>( DAY_OF_MONTH, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
			case HqlParser.WEEK:
				return new SqmExtractUnit<>( DAY_OF_WEEK, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
			case HqlParser.YEAR:
				return new SqmExtractUnit<>( DAY_OF_YEAR, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
		}
		throw new ParsingException("Unsupported day field [" + ctx.getText() + "]");
	}

	@Override
	public Object visitWeekField(HqlParser.WeekFieldContext ctx) {
		final NodeBuilder nodeBuilder = creationContext.getNodeBuilder();
		switch ( ( (TerminalNode) ctx.getChild( 2 ) ).getSymbol().getType() ) {
			case HqlParser.MONTH:
				//this is computed from DAY_OF_MONTH/7
				return new SqmExtractUnit<>( WEEK_OF_MONTH, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
			case HqlParser.YEAR:
				//this is computed from DAY_OF_YEAR/7
				return new SqmExtractUnit<>( WEEK_OF_YEAR, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
		}
		throw new ParsingException("Unsupported week field [" + ctx.getText() + "]");
	}

	@Override
	public Object visitDateOrTimeField(HqlParser.DateOrTimeFieldContext ctx) {
		final NodeBuilder nodeBuilder = creationContext.getNodeBuilder();
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.DATE:
				return isExtractingJdbcTemporalType
						? new SqmExtractUnit<>( DATE, resolveExpressableTypeBasic( Date.class ), nodeBuilder )
						: new SqmExtractUnit<>( DATE, resolveExpressableTypeBasic( LocalDate.class ), nodeBuilder );
			case HqlParser.TIME:
				return isExtractingJdbcTemporalType
						? new SqmExtractUnit<>( TIME, resolveExpressableTypeBasic( Time.class ), nodeBuilder )
						: new SqmExtractUnit<>( TIME, resolveExpressableTypeBasic( LocalTime.class ), nodeBuilder );
		}
		throw new ParsingException("Unsupported date or time field [" + ctx.getText() + "]");
	}

	@Override
	public Object visitTimeZoneField(HqlParser.TimeZoneFieldContext ctx) {
		final NodeBuilder nodeBuilder = creationContext.getNodeBuilder();
		switch ( ( (TerminalNode) ctx.getChild( ctx.getChildCount() - 1 ) ).getSymbol().getType() ) {
			case HqlParser.TIMEZONE_HOUR:
				return new SqmExtractUnit<>( TIMEZONE_HOUR, resolveExpressableTypeBasic( Integer.class ), nodeBuilder );
			case HqlParser.TIMEZONE_MINUTE:
				return new SqmExtractUnit<>(
						TIMEZONE_MINUTE,
						resolveExpressableTypeBasic( Integer.class ),
						nodeBuilder
				);
			default:
				return new SqmExtractUnit<>( OFFSET, resolveExpressableTypeBasic( ZoneOffset.class ), nodeBuilder );
		}
	}

	private boolean isExtractingJdbcTemporalType;

	@Override
	public Object visitExtractFunction(HqlParser.ExtractFunctionContext ctx) {
		final SqmExpression<?> expressionToExtract = (SqmExpression<?>) ctx.getChild( ctx.getChildCount() - 2 )
				.accept( this );

		// visitDateOrTimeField() needs to know if we're extracting from a
		// JDBC Timestamp or from a java.time LocalDateTime/OffsetDateTime
		isExtractingJdbcTemporalType = isJdbcTemporalType( expressionToExtract.getNodeType() );

		final SqmExtractUnit<?> extractFieldExpression;
		if ( ctx.getChild( 0 ) instanceof TerminalNode ) {
			//for the case of the full ANSI syntax "extract(field from arg)"
			extractFieldExpression = (SqmExtractUnit<?>) ctx.getChild( 2 ).accept(this);
		}
		else {
			//for the shorter legacy Hibernate syntax "field(arg)"
			extractFieldExpression = (SqmExtractUnit<?>) ctx.getChild( 0 ).accept(this);
		}

		return getFunctionDescriptor("extract").generateSqmExpression(
				asList( extractFieldExpression, expressionToExtract ),
				extractFieldExpression.getType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	// G era
	// y year in era
	// Y week year (ISO)
	// M month in year
	// w week in year (ISO)
	// W week in month
	// E day name in week
	// e day number in week (*very* inconsistent across DBs)
	// d day in month
	// D day in year
	// a AM/PM
	// H hour of day (0-23)
	// h clock hour of am/pm (1-12)
	// m minute of hour
	// s second of minute
	// S fraction of second
	// z time zone name e.g. PST
	// x zone offset e.g. +03, +0300, +03:00
	// Z zone offset e.g. +0300
	// see https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html
	private static final Pattern FORMAT = Pattern.compile("('[^']+'|[:;/,.!@#$^&?~`|()\\[\\]{}<>\\-+*=]|\\s|G{1,2}|[yY]{1,4}|M{1,4}|w{1,2}|W|E{3,4}|e{1,2}|d{1,2}|D{1,3}|a{1,2}|[Hhms]{1,2}|S{1,6}|[zZx]{1,3})*");

	@Override
	public Object visitFormat(HqlParser.FormatContext ctx) {
		String format = ctx.getChild( 0 ).getText();
		if (!FORMAT.matcher(format).matches()) {
			throw new SemanticException("illegal format pattern: '" + format + "'");
		}
		return new SqmFormat(
				format,
				resolveExpressableTypeBasic( String.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitFormatFunction(HqlParser.FormatFunctionContext ctx) {
		final SqmExpression<?> expressionToCast = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmLiteral<?> format = (SqmLiteral<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("format").generateSqmExpression(
				asList( expressionToCast, format ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitCastFunction(HqlParser.CastFunctionContext ctx) {
		final SqmExpression<?> expressionToCast = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmCastTarget<?> castTargetExpression = (SqmCastTarget<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("cast").generateSqmExpression(
				asList( expressionToCast, castTargetExpression ),
				castTargetExpression.getType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmCastTarget<?> visitCastTarget(HqlParser.CastTargetContext castTargetContext) {
		final HqlParser.CastTargetTypeContext castTargetTypeContext = (HqlParser.CastTargetTypeContext) castTargetContext.getChild( 0 );
		final String targetName = castTargetTypeContext.fullTargetName;

		Long length = null;
		Integer precision = null;
		Integer scale = null;
		switch ( castTargetTypeContext.getChildCount() ) {
			case 6:
				scale = Integer.valueOf( castTargetTypeContext.getChild( 4 ).getText() );
			case 4:
				length = Long.valueOf( castTargetTypeContext.getChild( 2 ).getText() );
				precision = length.intValue();
				break;
		}

		return new SqmCastTarget<>(
				(AllowableFunctionReturnType<?>)
						creationContext.getJpaMetamodel().getTypeConfiguration()
								.resolveCastTargetType( targetName ),
				//TODO: is there some way to interpret as length vs precision/scale here at this point?
				length,
				precision,
				scale,
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitUpperFunction(HqlParser.UpperFunctionContext ctx) {
		final SqmExpression<?> expression = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		return getFunctionDescriptor("upper").generateSqmExpression(
				expression,
				(AllowableFunctionReturnType<?>) expression.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitLowerFunction(HqlParser.LowerFunctionContext ctx) {
		// todo (6.0) : why pass both the expression and its expression-type?
		//			can't we just pass the expression?
		final SqmExpression<?> expression = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		return getFunctionDescriptor("lower").generateSqmExpression(
				expression,
				(AllowableFunctionReturnType<?>) expression.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitConcatFunction(HqlParser.ConcatFunctionContext ctx) {
		final int size = ctx.getChildCount();
		// Shift 1 bit instead of division by 2
		final int estimateArgumentCount = (size >> 1) - 1;
		final List<SqmTypedNode<?>> arguments = new ArrayList<>( estimateArgumentCount );
		for ( int i = 2; i < size; i += 2 ) {
			arguments.add( (SqmTypedNode<?>) ctx.getChild( i ).accept( this ) );
		}

		return getFunctionDescriptor("concat").generateSqmExpression(
				arguments,
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitLengthFunction(HqlParser.LengthFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );

		return getFunctionDescriptor("length").generateSqmExpression(
				arg,
				resolveExpressableTypeBasic( Integer.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitPositionFunction(HqlParser.PositionFunctionContext ctx) {
		final SqmExpression<?> pattern = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> string = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("position").generateSqmExpression(
				asList( pattern, string ),
				resolveExpressableTypeBasic( Integer.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitLocateFunction(HqlParser.LocateFunctionContext ctx) {
		final SqmExpression<?> pattern = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> string = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );
		final SqmExpression<?> start;
		if ( ctx.getChildCount() == 8 ) {
			start = (SqmExpression<?>) ctx.getChild( 6 ).accept( this );
		}
		else {
			start = null;
		}

		return getFunctionDescriptor("locate").generateSqmExpression(
				start == null
						? asList( pattern, string )
						: asList( pattern, string, start ),
				resolveExpressableTypeBasic( Integer.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitOverlayFunction(HqlParser.OverlayFunctionContext ctx) {
		final SqmExpression<?> string = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> replacement = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );
		final SqmExpression<?> start = (SqmExpression<?>) ctx.getChild( 6 ).accept( this );
		final SqmExpression<?> length;
		if ( ctx.getChildCount() == 10 ) {
			length = (SqmExpression<?>) ctx.getChild( 8 ).accept( this );
		}
		else {
			length = null;
		}

		return getFunctionDescriptor("overlay").generateSqmExpression(
				length == null
						? asList( string, replacement, start )
						: asList( string, replacement, start, length ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitReplaceFunction(HqlParser.ReplaceFunctionContext ctx) {
		final SqmExpression<?> string = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> pattern = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );
		final SqmExpression<?> replacement = (SqmExpression<?>) ctx.getChild( 6 ).accept( this );

		return getFunctionDescriptor("replace").generateSqmExpression(
				asList( string, pattern, replacement ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public Object visitStrFunction(HqlParser.StrFunctionContext ctx) {
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		return getFunctionDescriptor("str").generateSqmExpression(
				singletonList( arg ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitMaxFunction(HqlParser.MaxFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final int expressionIndex = ctx.getChildCount() - ( filterExpression == null ? 2 : 3 );
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( expressionIndex ).accept( this );
		//ignore DISTINCT
		return getFunctionDescriptor("max").generateAggregateSqmExpression(
				singletonList( arg ),
				filterExpression,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitMinFunction(HqlParser.MinFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final int expressionIndex = ctx.getChildCount() - ( filterExpression == null ? 2 : 3 );
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( expressionIndex ).accept( this );
		//ignore DISTINCT
		return getFunctionDescriptor("min").generateAggregateSqmExpression(
				singletonList( arg ),
				filterExpression,
				(AllowableFunctionReturnType<?>) arg.getNodeType(),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitSumFunction(HqlParser.SumFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final int expressionIndex = ctx.getChildCount() - ( filterExpression == null ? 2 : 3 );
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( expressionIndex ).accept( this );

		return getFunctionDescriptor("sum").generateAggregateSqmExpression(
				singletonList( applyDistinct( arg, ctx ) ),
				filterExpression,
				null,
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitEveryFunction(HqlParser.EveryFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final ParseTree argumentChild = ctx.getChild( 2 );
		if ( argumentChild instanceof HqlParser.SubQueryContext ) {
			SqmSubQuery<?> subquery = (SqmSubQuery<?>) argumentChild.accept(this);
			return new SqmEvery<>( subquery, creationContext.getNodeBuilder() );
		}

		final SqmExpression<?> argument = (SqmExpression<?>) argumentChild.accept( this );

		if ( argument instanceof SqmSubQuery<?> && ctx.getChild( ctx.getChildCount() - 1) instanceof HqlParser.FilterClauseContext ) {
			throw new SemanticException( "Quantified expression cannot have a filter clause!" );
		}

		return getFunctionDescriptor("every").generateAggregateSqmExpression(
				singletonList( argument ),
				filterExpression,
				resolveExpressableTypeBasic( Boolean.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitAnyFunction(HqlParser.AnyFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final ParseTree argumentChild = ctx.getChild( 2 );
		if ( argumentChild instanceof HqlParser.SubQueryContext ) {
			SqmSubQuery<?> subquery = (SqmSubQuery<?>) argumentChild.accept(this);
			return new SqmAny<>( subquery, creationContext.getNodeBuilder() );
		}

		final SqmExpression<?> argument = (SqmExpression<?>) argumentChild.accept( this );

		if ( argument instanceof SqmSubQuery<?> && ctx.getChild( ctx.getChildCount() - 1) instanceof HqlParser.FilterClauseContext ) {
			throw new SemanticException( "Quantified expression cannot have a filter clause!" );
		}

		return getFunctionDescriptor("any").generateAggregateSqmExpression(
				singletonList( argument ),
				filterExpression,
				resolveExpressableTypeBasic( Boolean.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitAvgFunction(HqlParser.AvgFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final int expressionIndex = ctx.getChildCount() - ( filterExpression == null ? 2 : 3 );
		final SqmExpression<?> arg = (SqmExpression<?>) ctx.getChild( expressionIndex ).accept( this );

		return getFunctionDescriptor("avg").generateAggregateSqmExpression(
				singletonList( applyDistinct( arg, ctx ) ),
				filterExpression,
				resolveExpressableTypeBasic( Double.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitCountFunction(HqlParser.CountFunctionContext ctx) {
		final SqmPredicate filterExpression = getFilterExpression( ctx );
		final int expressionIndex = ctx.getChildCount() - ( filterExpression == null ? 2 : 3 );
		final ParseTree argumentChild = ctx.getChild( expressionIndex );
		final SqmExpression<?> arg;
		if ( argumentChild instanceof TerminalNode ) {
			arg = new SqmStar( getCreationContext().getNodeBuilder() );
		}
		else {
			arg = (SqmExpression<?>) argumentChild.accept( this );
		}

		return getFunctionDescriptor("count").generateAggregateSqmExpression(
				singletonList( applyDistinct( arg, ctx ) ),
				filterExpression,
				resolveExpressableTypeBasic( Long.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	private SqmTypedNode<?> applyDistinct(SqmExpression<?> expression, ParseTree functionCtx) {
		final ParseTree node = functionCtx.getChild( 2 );
		if ( node instanceof TerminalNode && ( (TerminalNode) node ).getSymbol().getType() == HqlParser.DISTINCT ) {
			return new SqmDistinct<>( expression, getCreationContext().getNodeBuilder() );
		}
		return expression;
	}

	private SqmPredicate getFilterExpression(ParseTree functionCtx) {
		final ParseTree lastChild = functionCtx.getChild( functionCtx.getChildCount() - 1 );
		if ( lastChild instanceof HqlParser.FilterClauseContext ) {
			return (SqmPredicate) lastChild.getChild( 2 ).getChild( 1 ).accept( this );
		}
		return null;
	}

	@Override
	public SqmExpression<?> visitCube(HqlParser.CubeContext ctx) {
		return new SqmSummarization<>(
				SqmSummarization.Kind.CUBE,
				visitExpressions( ctx ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitRollup(HqlParser.RollupContext ctx) {
		return new SqmSummarization<>(
				SqmSummarization.Kind.ROLLUP,
				visitExpressions( ctx ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitSubstringFunction(HqlParser.SubstringFunctionContext ctx) {
		final SqmExpression<?> source = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> start = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );
		final SqmExpression<?> length;
		if ( ctx.getChildCount() == 8 ) {
			length = (SqmExpression<?>) ctx.getChild( 6 ).accept( this );
		}
		else {
			length = null;
		}

		return getFunctionDescriptor("substring").generateSqmExpression(
				length == null ? asList( source, start ) : asList( source, start, length ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitLeftFunction(HqlParser.LeftFunctionContext ctx) {
		final SqmExpression<?> source = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> length = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("left").generateSqmExpression(
				asList( source, length ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getNodeBuilder().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitRightFunction(HqlParser.RightFunctionContext ctx) {
		final SqmExpression<?> source = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> length = (SqmExpression<?>) ctx.getChild( 4 ).accept( this );

		return getFunctionDescriptor("right").generateSqmExpression(
				asList( source, length ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getNodeBuilder().getTypeConfiguration()
		);
	}

	@Override
	public SqmExpression<?> visitPadFunction(HqlParser.PadFunctionContext ctx) {
		final SqmExpression<?> source = (SqmExpression<?>) ctx.getChild( 2 ).accept( this );
		final SqmExpression<?> length = (SqmExpression<?>) ctx.getChild( 4 ).accept(this);
		final SqmTrimSpecification padSpec = visitPadSpecification( (HqlParser.PadSpecificationContext) ctx.getChild( 5 ) );
		final SqmLiteral<Character> padChar;
		if ( ctx.getChildCount() == 8 ) {
			padChar = visitPadCharacter( (HqlParser.PadCharacterContext) ctx.getChild( 6 ) );
		}
		else {
			padChar = null;
		}
		return getFunctionDescriptor("pad").generateSqmExpression(
				padChar != null
						? asList( source, length, padSpec, padChar )
						: asList( source, length, padSpec ),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmTrimSpecification visitPadSpecification(HqlParser.PadSpecificationContext ctx) {
		switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
			case HqlParser.LEADING:
				return new SqmTrimSpecification( TrimSpec.LEADING, creationContext.getNodeBuilder() );
			case HqlParser.TRAILING:
				return new SqmTrimSpecification( TrimSpec.TRAILING, creationContext.getNodeBuilder() );
		}
		throw new ParsingException("Unsupported pad specification [" + ctx.getText() + "]");
	}

	@Override
	public SqmLiteral<Character> visitPadCharacter(HqlParser.PadCharacterContext ctx) {
		// todo (6.0) : we should delay this until we are walking the SQM

		final String padCharText = ctx.STRING_LITERAL().getText();

		if ( padCharText.length() != 1 ) {
			throw new SemanticException( "Pad character for pad() function must be single character, found: " + padCharText );
		}

		return new SqmLiteral<>(
				padCharText.charAt( 0 ),
				resolveExpressableTypeBasic( Character.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmExpression<?> visitTrimFunction(HqlParser.TrimFunctionContext ctx) {
		final SqmExpression<?> source = (SqmExpression<?>) ctx.getChild( ctx.getChildCount() - 2 ).accept( this );
		final SqmTrimSpecification trimSpec;
		final SqmLiteral<Character> trimChar;
		int index = 2;
		ParseTree parseTree = ctx.getChild( index );
		if ( parseTree instanceof HqlParser.TrimSpecificationContext ) {
			trimSpec = visitTrimSpecification( (HqlParser.TrimSpecificationContext) parseTree );
			index = 3;
		}
		else {
			trimSpec = visitTrimSpecification( null );
		}
		parseTree = ctx.getChild( index );
		if ( parseTree instanceof HqlParser.TrimCharacterContext ) {
			trimChar = visitTrimCharacter( (HqlParser.TrimCharacterContext) parseTree );
		}
		else {
			trimChar = visitTrimCharacter( null );
		}

		return getFunctionDescriptor("trim").generateSqmExpression(
				asList(
						trimSpec,
						trimChar,
						source
				),
				resolveExpressableTypeBasic( String.class ),
				creationContext.getQueryEngine(),
				creationContext.getJpaMetamodel().getTypeConfiguration()
		);
	}

	@Override
	public SqmTrimSpecification visitTrimSpecification(HqlParser.TrimSpecificationContext ctx) {
		TrimSpec spec = TrimSpec.BOTH;	// JPA says the default is BOTH

		if ( ctx != null ) {
			switch ( ( (TerminalNode) ctx.getChild( 0 ) ).getSymbol().getType() ) {
				case HqlParser.LEADING:
					spec = TrimSpec.LEADING;
					break;
				case HqlParser.TRAILING:
					spec = TrimSpec.TRAILING;
					break;
			}
		}

		return new SqmTrimSpecification( spec, creationContext.getNodeBuilder() );
	}

	@Override
	public SqmLiteral<Character> visitTrimCharacter(HqlParser.TrimCharacterContext ctx) {
		// todo (6.0) : we should delay this until we are walking the SQM

		final String trimCharText = ctx != null
				? ctx.getText()
				: " "; // JPA says space is the default

		if ( trimCharText.length() != 1 ) {
			throw new SemanticException( "Trim character for trim() function must be single character, found: " + trimCharText );
		}

		return new SqmLiteral<>(
				trimCharText.charAt( 0 ),
				resolveExpressableTypeBasic( Character.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmCollectionSize visitCollectionSizeFunction(HqlParser.CollectionSizeFunctionContext ctx) {
		return new SqmCollectionSize(
				consumeDomainPath( (HqlParser.PathContext) ctx.getChild( 2 ) ),
				resolveExpressableTypeBasic( Integer.class ),
				creationContext.getNodeBuilder()
		);
	}

	@Override
	public SqmPath<?> visitCollectionIndexFunction(HqlParser.CollectionIndexFunctionContext ctx) {
		final String alias = ctx.getChild( 2 ).getText();
		final SqmFrom<?, ?> sqmFrom = processingStateStack.getCurrent().getPathRegistry().findFromByAlias( alias );

		if ( sqmFrom == null ) {
			throw new ParsingException( "Could not resolve identification variable [" + alias + "] to SqmFrom" );
		}

		final SqmPathSource<?> pluralAttribute = sqmFrom.getReferencedPathSource();

		if ( !( pluralAttribute instanceof PluralPersistentAttribute ) ) {
			throw new ParsingException( "Could not resolve identification variable [" + alias + "] as plural-attribute" );
		}

		return ( (PluralPersistentAttribute<?, ?, ?>) pluralAttribute ).getIndexPathSource().createSqmPath(
				sqmFrom
		);
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private boolean isIndexedPluralAttribute(SqmPath<?> path) {
		return path.getReferencedPathSource() instanceof PluralPersistentAttribute;
	}

	@Override
	public SqmMaxElementPath<?> visitMaxElementFunction(HqlParser.MaxElementFunctionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation( StrictJpaComplianceViolation.Type.HQL_COLLECTION_FUNCTION );
		}

		return new SqmMaxElementPath<>( consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 2 ) ) );
	}

	@Override
	public SqmMinElementPath<?> visitMinElementFunction(HqlParser.MinElementFunctionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation( StrictJpaComplianceViolation.Type.HQL_COLLECTION_FUNCTION );
		}

		return new SqmMinElementPath<>( consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 2 ) ) );
	}

	@Override
	public SqmMaxIndexPath<?> visitMaxIndexFunction(HqlParser.MaxIndexFunctionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation( StrictJpaComplianceViolation.Type.HQL_COLLECTION_FUNCTION );
		}

		final SqmPath<?> pluralPath = consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 2 ) );
		if ( !isIndexedPluralAttribute( pluralPath ) ) {
			throw new SemanticException(
					"maxindex() function can only be applied to path expressions which resolve to an " +
							"indexed collection (list,map); specified path [" + ctx.getChild( 2 ).getText() +
							"] resolved to " + pluralPath.getReferencedPathSource()
			);
		}

		return new SqmMaxIndexPath<>( pluralPath );
	}

	@Override
	public SqmMinIndexPath<?> visitMinIndexFunction(HqlParser.MinIndexFunctionContext ctx) {
		if ( creationOptions.useStrictJpaCompliance() ) {
			throw new StrictJpaComplianceViolation( StrictJpaComplianceViolation.Type.HQL_COLLECTION_FUNCTION );
		}

		final SqmPath<?> pluralPath = consumePluralAttributeReference( (HqlParser.PathContext) ctx.getChild( 2 ) );
		if ( !isIndexedPluralAttribute( pluralPath ) ) {
			throw new SemanticException(
					"minindex() function can only be applied to path expressions which resolve to an " +
							"indexed collection (list,map); specified path [" + ctx.getChild( 2 ).getText() +
							"] resolved to " + pluralPath.getReferencedPathSource()
			);
		}

		return new SqmMinIndexPath<>( pluralPath );
	}

	@Override
	public SqmSubQuery<?> visitSubQueryExpression(HqlParser.SubQueryExpressionContext ctx) {
		return visitSubQuery( (HqlParser.SubQueryContext) ctx.getChild( 1 ) );
	}

	@Override
	public SqmSubQuery<?> visitSubQuery(HqlParser.SubQueryContext ctx) {
		final HqlParser.QueryExpressionContext queryExpressionContext = (HqlParser.QueryExpressionContext) ctx.getChild( 0 );
		final SqmSubQuery<?> subQuery = new SqmSubQuery<>(
				processingStateStack.getCurrent().getProcessingQuery(),
				creationContext.getNodeBuilder()
		);

		processingStateStack.push(
				new SqmQuerySpecCreationProcessingStateStandardImpl(
						processingStateStack.getCurrent(),
						subQuery,
						this
				)
		);

		try {
			queryExpressionContext.accept( this );

			final List<SqmSelection<?>> selections = subQuery.getQuerySpec().getSelectClause().getSelections();
			if ( selections.size() == 1 ) {
				subQuery.applyInferableType( selections.get( 0 ).getNodeType() );
			}

			return subQuery;
		}
		finally {
			processingStateStack.pop();
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Path structures

	@Override
	public SemanticPathPart visitPath(HqlParser.PathContext ctx) {
		final ParseTree firstChild = ctx.getChild( 0 );
		if ( firstChild instanceof HqlParser.SyntacticDomainPathContext ) {
			final SemanticPathPart syntacticNavigablePathResult = visitSyntacticDomainPath( (HqlParser.SyntacticDomainPathContext) firstChild );
			if ( ctx.getChildCount() == 2 ) {
				dotIdentifierConsumerStack.push(
						new BasicDotIdentifierConsumer( syntacticNavigablePathResult, this ) {
							@Override
							protected void reset() {
							}
						}
				);
				try {
					return (SemanticPathPart) ctx.getChild( 1 ).accept( this );
				}
				finally {
					dotIdentifierConsumerStack.pop();
				}
			}
			return syntacticNavigablePathResult;
		}
		else if ( firstChild instanceof HqlParser.GeneralPathFragmentContext ) {
			return (SemanticPathPart) firstChild.accept( this );
		}

		throw new ParsingException( "Unrecognized `path` rule branch" );
	}

	@Override
	public SemanticPathPart visitGeneralPathFragment(HqlParser.GeneralPathFragmentContext ctx) {
		return visitDotIdentifierSequence( (HqlParser.DotIdentifierSequenceContext) ctx.getChild( 0 ) );
	}

	@Override
	public SemanticPathPart visitSyntacticDomainPath(HqlParser.SyntacticDomainPathContext ctx) {
		final ParseTree firstChild = ctx.getChild( 0 );
		if ( firstChild instanceof HqlParser.TreatedNavigablePathContext ) {
			return visitTreatedNavigablePath( (HqlParser.TreatedNavigablePathContext) firstChild );
		}
		else if ( firstChild instanceof HqlParser.CollectionElementNavigablePathContext ) {
			return visitCollectionElementNavigablePath( (HqlParser.CollectionElementNavigablePathContext) firstChild );
		}
		else if ( firstChild instanceof HqlParser.MapKeyNavigablePathContext ) {
			return visitMapKeyNavigablePath( (HqlParser.MapKeyNavigablePathContext) firstChild );
		}
		else if ( firstChild instanceof HqlParser.DotIdentifierSequenceContext && ctx.getChildCount() == 2 ) {
			dotIdentifierConsumerStack.push(
					new QualifiedJoinPathConsumer(
							(SqmRoot<?>) dotIdentifierConsumerStack.getCurrent().getConsumedPart(),
							SqmJoinType.INNER,
							false,
							null,
							this
					)
			);

			final SqmAttributeJoin<?, ?> indexedJoinPath;
			try {
				indexedJoinPath = (SqmAttributeJoin<?, ?>) firstChild.accept( this );
			}
			finally {
				dotIdentifierConsumerStack.pop();
			}
			dotIdentifierConsumerStack.push(
					new BasicDotIdentifierConsumer( indexedJoinPath, this ) {
						@Override
						protected void reset() {
						}
					}
			);
			try {
				return (SemanticPathPart) ctx.getChild( 1 ).accept( this );
			}
			finally {
				dotIdentifierConsumerStack.pop();
			}
		}

		throw new ParsingException( "Unsure how to process `syntacticDomainPath` over : " + ctx.getText() );
	}

	@Override
	public SemanticPathPart visitIndexedPathAccessFragment(HqlParser.IndexedPathAccessFragmentContext ctx) {
		final DotIdentifierConsumer consumer = dotIdentifierConsumerStack.pop();
		final SqmExpression<?> indexExpression = (SqmExpression<?>) ctx.getChild( 1 ).accept( this );
		final SqmAttributeJoin<?, ?> attributeJoin = (SqmAttributeJoin<?, ?>) consumer.getConsumedPart();
		final SqmExpression<?> index;
		if ( attributeJoin instanceof SqmListJoin<?, ?> ) {
			index = ( (SqmListJoin<?, ?>) attributeJoin ).index();
		}
		else if ( attributeJoin instanceof SqmMapJoin<?, ?, ?> ) {
			index = ( (SqmMapJoin<?, ?, ?>) attributeJoin ).key();
		}
		else {
			throw new SemanticException( "Index access is only supported on list or map attributes: " + attributeJoin.getNavigablePath() );
		}
		attributeJoin.setJoinPredicate( creationContext.getNodeBuilder().equal( index, indexExpression ) );
		final SqmIndexedCollectionAccessPath<?> path = new SqmIndexedCollectionAccessPath<>(
				attributeJoin,
				indexExpression
		);
		dotIdentifierConsumerStack.push(
				new BasicDotIdentifierConsumer( path, this ) {
					@Override
					protected void reset() {
					}
				}
		);
		if ( ctx.getChildCount() == 5 ) {
			return (SemanticPathPart) ctx.getChild( 4 ).accept( this );
		}
		return path;
	}

	@Override
	public SemanticPathPart visitDotIdentifierSequence(HqlParser.DotIdentifierSequenceContext ctx) {
		final int numberOfContinuations = ctx.getChildCount() - 1;
		final boolean hasContinuations = numberOfContinuations != 0;

		final DotIdentifierConsumer dotIdentifierConsumer = dotIdentifierConsumerStack.getCurrent();
		final HqlParser.IdentifierContext identifierContext = (HqlParser.IdentifierContext) ctx.getChild( 0 );
		assert identifierContext.getChildCount() == 1;

		dotIdentifierConsumer.consumeIdentifier(
				identifierContext.getChild( 0 ).getText(),
				true,
				! hasContinuations
		);

		if ( hasContinuations ) {
			for ( int i = 1; i < ctx.getChildCount(); i++ ) {
				final HqlParser.DotIdentifierSequenceContinuationContext continuation = (HqlParser.DotIdentifierSequenceContinuationContext) ctx.getChild( i );
				final HqlParser.IdentifierContext identifier = (HqlParser.IdentifierContext) continuation.getChild( 1 );
				assert identifier.getChildCount() == 1;
				dotIdentifierConsumer.consumeIdentifier(
						identifier.getChild( 0 ).getText(),
						false,
						i >= numberOfContinuations
				);
			}
		}

		return dotIdentifierConsumer.getConsumedPart();
	}

	@Override
	public SqmPath<?> visitTreatedNavigablePath(HqlParser.TreatedNavigablePathContext ctx) {
		final SqmPath<?> sqmPath = consumeManagedTypeReference( (HqlParser.PathContext) ctx.getChild( 2 ) );

		final String treatTargetName = ctx.getChild( 4 ).getText();
		final String treatTargetEntityName = getCreationContext().getJpaMetamodel().qualifyImportableName( treatTargetName );
		final EntityDomainType<?> treatTarget = getCreationContext().getJpaMetamodel().entity( treatTargetEntityName );

		SqmPath<?> result = resolveTreatedPath( sqmPath, treatTarget );

		if ( ctx.getChildCount() == 7 ) {
			dotIdentifierConsumerStack.push(
					new BasicDotIdentifierConsumer( result, this ) {
						@Override
						protected void reset() {
						}
					}
			);
			try {
				result = consumeDomainPath( (HqlParser.DotIdentifierSequenceContext) ctx.getChild( 6 ).getChild( 1 ) );
			}
			finally {
				dotIdentifierConsumerStack.pop();
			}
		}

		return result;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private SqmTreatedPath<?, ?> resolveTreatedPath(SqmPath<?> sqmPath, EntityDomainType<?> treatTarget) {
		return sqmPath.treatAs( (EntityDomainType) treatTarget );
	}

	@Override
	public SqmPath<?> visitCollectionElementNavigablePath(HqlParser.CollectionElementNavigablePathContext ctx) {
		final SqmPath<?> pluralAttributePath = consumeDomainPath( (HqlParser.PathContext) ctx.getChild( 2 ) );
		final SqmPathSource<?> referencedPathSource = pluralAttributePath.getReferencedPathSource();

		if ( !(referencedPathSource instanceof PluralPersistentAttribute ) ) {
			throw new PathException(
					"Illegal attempt to treat non-plural path as a plural path : " + pluralAttributePath.getNavigablePath()
			);
		}

		final PluralPersistentAttribute<?, ?, ?> attribute = (PluralPersistentAttribute<?, ?, ?>) referencedPathSource;

		if ( getCreationOptions().useStrictJpaCompliance() ) {
			if ( attribute.getCollectionClassification() != CollectionClassification.MAP ) {
				throw new StrictJpaComplianceViolation( StrictJpaComplianceViolation.Type.VALUE_FUNCTION_ON_NON_MAP );
			}
		}

		SqmPath<?> result = attribute.getElementPathSource().createSqmPath(
				pluralAttributePath
		);

		if ( ctx.getChildCount() == 5 ) {
			result = consumeDomainPath( (HqlParser.DotIdentifierSequenceContext) ctx.getChild( 4 ).getChild( 1 ) );
		}

		return result;
	}


	@Override
	@SuppressWarnings({ "rawtypes" })
	public SqmPath visitMapKeyNavigablePath(HqlParser.MapKeyNavigablePathContext ctx) {
		final SqmPath<?> sqmPath = consumeDomainPath( (HqlParser.PathContext) ctx.getChild( 2 ) );

		SqmPath<?> result;
		if ( sqmPath instanceof SqmMapJoin ) {
			final SqmMapJoin<?, ?, ?> sqmMapJoin = (SqmMapJoin<?, ?, ?>) sqmPath;
			result = sqmMapJoin.getReferencedPathSource().getIndexPathSource().createSqmPath( sqmMapJoin );
		}
		else {
			assert sqmPath instanceof SqmPluralValuedSimplePath;
			final SqmPluralValuedSimplePath<?> mapPath = (SqmPluralValuedSimplePath<?>) sqmPath;
			final SqmPath<?> keyPath = mapPath.getReferencedPathSource()
					.getIndexPathSource()
					.createSqmPath( mapPath );
			mapPath.registerReusablePath( keyPath );
			result = keyPath;
		}

		if ( ctx.getChildCount() == 5 ) {
			result = consumeDomainPath( (HqlParser.DotIdentifierSequenceContext) ctx.getChild( 4 ).getChild( 1 ) );
		}

		return result;
	}

	private SqmPath<?> consumeDomainPath(HqlParser.PathContext parserPath) {
		final SemanticPathPart consumedPart = (SemanticPathPart) parserPath.accept( this );
		if ( consumedPart instanceof SqmPath ) {
			return (SqmPath<?>) consumedPart;
		}

		throw new SemanticException( "Expecting domain-model path, but found : " + consumedPart );
	}


	private SqmPath<?> consumeDomainPath(HqlParser.DotIdentifierSequenceContext sequence) {
		final SemanticPathPart consumedPart = (SemanticPathPart) sequence.accept( this );
		if ( consumedPart instanceof SqmPath ) {
			return (SqmPath<?>) consumedPart;
		}

		throw new SemanticException( "Expecting domain-model path, but found : " + consumedPart );
	}

	private SqmPath<?> consumeManagedTypeReference(HqlParser.PathContext parserPath) {
		final SqmPath<?> sqmPath = consumeDomainPath( parserPath );

		final SqmPathSource<?> pathSource = sqmPath.getReferencedPathSource();

		try {
			// use the `#sqmAs` call to validate the path is a ManagedType
			pathSource.sqmAs( ManagedDomainType.class );
			return sqmPath;
		}
		catch (Exception e) {
			throw new SemanticException( "Expecting ManagedType valued path [" + sqmPath.getNavigablePath() + "], but found : " + pathSource.getSqmPathType() );
		}
	}

	private SqmPath<?> consumePluralAttributeReference(HqlParser.PathContext parserPath) {
		final SqmPath<?> sqmPath = consumeDomainPath( parserPath );

		if ( sqmPath.getReferencedPathSource() instanceof PluralPersistentAttribute ) {
			return sqmPath;
		}

		throw new SemanticException( "Expecting plural attribute valued path [" + sqmPath.getNavigablePath() + "], but found : " + sqmPath.getReferencedPathSource().getSqmPathType() );
	}

	private interface TreatHandler {
		void addDowncast(SqmFrom<?, ?> sqmFrom, IdentifiableDomainType<?> downcastTarget);
	}

	private static class TreatHandlerNormal implements TreatHandler {
		private final DowncastLocation downcastLocation;

		public TreatHandlerNormal() {
			this( DowncastLocation.OTHER );
		}

		public TreatHandlerNormal(DowncastLocation downcastLocation) {
			this.downcastLocation = downcastLocation;
		}

		@Override
		public void addDowncast(
				SqmFrom<?, ?> sqmFrom,
				IdentifiableDomainType<?> downcastTarget) {
//			( (MutableUsageDetails) sqmFrom.getUsageDetails() ).addDownCast( false, downcastTarget, downcastLocation );
			throw new NotYetImplementedFor6Exception();
		}
	}

	private static class TreatHandlerFromClause implements TreatHandler {
		@Override
		public void addDowncast(
				SqmFrom<?, ?> sqmFrom,
				IdentifiableDomainType<?> downcastTarget) {
//			( (MutableUsageDetails) sqmFrom.getUsageDetails() ).addDownCast( true, downcastTarget, DowncastLocation.FROM );
			throw new NotYetImplementedFor6Exception();
		}
	}
	
	private void checkFQNEntityNameJpaComplianceViolationIfNeeded(String name, EntityDomainType<?> entityDescriptor) {
		if ( getCreationOptions().useStrictJpaCompliance() && ! name.equals( entityDescriptor.getName() ) ) {
			// FQN is the only possible reason
			throw new StrictJpaComplianceViolation("Encountered FQN entity name [" + name + "], " +
					"but strict JPQL compliance was requested ( [" + entityDescriptor.getName() + "] should be used instead )",
					StrictJpaComplianceViolation.Type.FQN_ENTITY_NAME
			);
		}
	}
}