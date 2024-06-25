package org.babyfish.jimmer.sql.ast.impl.mutation.save;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.impl.AstContext;
import org.babyfish.jimmer.sql.ast.impl.query.FilterLevel;
import org.babyfish.jimmer.sql.ast.impl.query.MutableRootQueryImpl;
import org.babyfish.jimmer.sql.ast.impl.render.AbstractSqlBuilder;
import org.babyfish.jimmer.sql.ast.impl.render.BatchSqlBuilder;
import org.babyfish.jimmer.sql.ast.impl.render.ComparisonPredicates;
import org.babyfish.jimmer.sql.ast.impl.table.TableImplementor;
import org.babyfish.jimmer.sql.ast.impl.value.ValueGetter;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.collection.TypedList;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.SqlBuilder;

import java.util.*;

class ChildTableOperator extends AbstractOperator {

    private final DeleteContext ctx;

    private final String tableName;

    private final List<ValueGetter> sourceGetters;

    private final List<ValueGetter> targetGetters;

    private final boolean hasTargetFilter;

    ChildTableOperator(DeleteContext ctx) {
        super(ctx.options.getSqlClient(), ctx.con);
        this.ctx = ctx;
        this.tableName = ctx.path.getType().getTableName(sqlClient.getMetadataStrategy());
        this.sourceGetters = ValueGetter.valueGetters(sqlClient, ctx.backReferenceProp);
        this.targetGetters = ValueGetter.valueGetters(sqlClient, ctx.path.getType().getIdProp());
        this.hasTargetFilter = sqlClient.getFilters().getTargetFilter(ctx.path.getProp()) != null;
    }

    private ChildTableOperator(ChildTableOperator parent, ImmutableProp prop) {
        this(parent.ctx.to(prop));
        if (ctx.backReferenceProp == null) {
            throw new IllegalArgumentException(
                    "The property \"" +
                            prop +
                            "\" is not association property with child table."
            );
        }
    }

    IdPairs findDisconnectingIdPairs(IdPairs idPairs) {
        MutableRootQueryImpl<Table<?>> query =
                new MutableRootQueryImpl<>(
                        sqlClient,
                        ctx.path.getType(),
                        ExecutionPurpose.MUTATE,
                        FilterLevel.DEFAULT
                );
        addDisconnectingConditions(query, idPairs);
        List<Tuple2<Object, Object>> tuples = query.select(
                query.getTableImplementor().getAssociatedId(ctx.backReferenceProp),
                query.getTableImplementor().getId()
        ).execute(con);
        return IdPairs.of(tuples);
    }

    int disconnectExcept(IdPairs idPairs) {
        return disconnectExceptImpl(idPairs);
    }

    private int disconnectExceptImpl(IdPairs idPairs) {
        if (idPairs.entries().size() < 2) {
            Tuple2<Object, Collection<Object>> idTuple = idPairs.entries().iterator().next();
            return disconnectExceptBySimpleInPredicate(idTuple.get_1(), idTuple.get_2());
        }
        if (targetGetters.size() == 1 && sqlClient.getDialect().isAnyEqualityOfArraySupported()) {
            return disconnectExceptByBatch(idPairs);
        }
        return disconnectExceptByComplexInPredicate(idPairs);
    }

    @SuppressWarnings("unchecked")
    private int disconnectExceptByBatch(IdPairs idPairs) {
        BatchSqlBuilder builder = new BatchSqlBuilder(sqlClient);
        builder.sql("update ")
                .sql(tableName)
                .enter(AbstractSqlBuilder.ScopeType.SET);
        for (ValueGetter sourceGetter : sourceGetters) {
            builder.separator()
                    .sql(sourceGetter)
                    .sql(" = null");
        }
        builder.leave();
        builder.enter(AbstractSqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters
        );
        builder.leave();
        return execute(builder, idPairs.entries());
    }

    private int disconnectExceptBySimpleInPredicate(Object sourceId, Collection<?> targetIds) {
        SqlBuilder builder = new SqlBuilder(new AstContext(sqlClient));
        builder.sql("update ")
                .sql(tableName)
                .enter(AbstractSqlBuilder.ScopeType.SET);
        for (ValueGetter sourceGetter : sourceGetters) {
            builder.separator()
                    .sql(sourceGetter)
                    .sql(" = null");
        }
        builder.leave();
        builder.enter(AbstractSqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters,
                sourceId,
                targetIds
        );
        builder.leave();
        return execute(builder);
    }

    private int disconnectExceptByComplexInPredicate(IdPairs idPairs) {
        SqlBuilder builder = new SqlBuilder(new AstContext(sqlClient));
        builder.sql("update ")
                .sql(tableName)
                .enter(AbstractSqlBuilder.ScopeType.SET);
        for (ValueGetter sourceGetter : sourceGetters) {
            builder.separator()
                    .sql(sourceGetter)
                    .sql(" = null");
        }
        builder.leave();
        builder.enter(AbstractSqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters,
                idPairs
        );
        builder.leave();
        return execute(builder);
    }

    private void addDisconnectingConditions(MutableRootQueryImpl<?> query, IdPairs idPairs) {
        TableImplementor<?> table = query.getTableImplementor();
        if (idPairs.entries().size() == 1) {
            query.where(
                    table.getAssociatedId(ctx.backReferenceProp).in(
                            Tuple2.projection1(idPairs.entries())
                    )
            );
            if (!idPairs.tuples().isEmpty()) {
                query.where(
                        table.getId().notIn(
                                Tuple2.projection2(idPairs.tuples())
                        )
                );
            }
            return;
        }
        query.where(
                table.getAssociatedId(ctx.backReferenceProp).in(
                        Tuple2.projection1(idPairs.entries())
                )
        );
        if (!idPairs.tuples().isEmpty()) {
            query.where(
                    Expression.tuple(
                            table.getAssociatedId(ctx.backReferenceProp),
                            table.getId()
                    ).notIn(idPairs.tuples())
            );
        }
    }
}