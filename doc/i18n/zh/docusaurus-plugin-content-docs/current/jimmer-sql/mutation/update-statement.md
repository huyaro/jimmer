---
sidebar_position: 1
title: Update语句
---

## 基本用法

Update语句用法如下

```java
int affectedRowCount = sqlClient
    .createUpdate(AuthorTable.class, (u, author) -> {
        u
            .set(
                author.firstName(), 
                author.firstName().concat("*")
            )
            .where(author.firstName().eq("Dan"));
    })
    .execute();
System.out.println("Affected row count: " + affectedRowCount);
```

最终生成的SQL

```sql
update AUTHOR tb_1_ 
set FIRST_NAME = concat(tb_1_.FIRST_NAME, ?) 
where tb_1_.FIRST_NAME = ?
```

## 使用JOIN

默认情况下，update语句不支持join，这会导致异常

```java
int affectedRowCount = sqlClient
    .createUpdate(AuthorTableEx.class, (u, author) -> {
        u
            .set(
                author.firstName(),
                author.firstName().concat("*")
            )
            .where(
                author
                    // highlight-next-line
                    .books()
                    .name()
                    .eq("Learning GraphQL")
            );
    })
    .execute();
System.out.println("Affected row count: " + affectedRowCount);
```

异常信息如下
:::caution
Table joins for update statement is forbidden by the current dialect, but there is a join `'Author.books'`.
:::

当使用MySql或Postgres时，update语句可以使用JOIN语句。

### MySql

首先，需要在创建SqlClient时，指定方言为MySqlDialect

```java
SqlClient sqlClient = SqlClient
    .newBuilder()
    .setDialect(
        new org.babyfish.jimmer.sql.dialect.MySqlDialect()
    )
    ...
    .build();
```

然后，就可以在update中使用JOIN了

```java
int affectedRowCount = sqlClient
        .createUpdate(AuthorTableEx.class, (u, author) -> {
            u
                .set(
                    author.firstName(), 
                    author.firstName().concat("*")
                )
                .set(
                    author.books().name(), 
                    author.books().name().concat("*")
                )
                .set(
                    author.books().store().name(), 
                    author.books().store().name().concat("*")
                )
                .where(
                    author
                        .books()
                        .store()
                        .name()
                        .eq("MANNING")
                );
        })
        .execute();
System.out.println("Affected row count: " + affectedRowCount);
```

最终生成针对MySQL的SQL语句，如下：

```sql
update 
    AUTHOR tb_1_ 
    inner join BOOK_AUTHOR_MAPPING as tb_2_ 
        on tb_1_.ID = tb_2_.AUTHOR_ID 
    inner join BOOK as tb_3_ 
        on tb_2_.BOOK_ID = tb_3_.ID 
    inner join BOOK_STORE as tb_4_ 
        on tb_3_.STORE_ID = tb_4_.ID 
set 
    tb_1_.FIRST_NAME = concat(tb_1_.FIRST_NAME, ?), 
    tb_3_.NAME = concat(tb_3_.NAME, ?), 
    tb_4_.NAME = concat(tb_4_.NAME, ?) 
where 
    tb_4_.NAME = ?
```

### Postgres

首先，需要在创建SqlClient时，指定方言为PostgresDialect

```java
SqlClient sqlClient = SqlClient
    .newBuilder()
    .setDialect(
        new org.babyfish.jimmer.sql.dialect.PostgresDialect()
    )
    ...
    .build();
```

然后，就可以在update中使用JOIN了

```java
int affectedRowCount = sqlClient
    .createUpdate(AuthorTableEx.class, (u, author) -> {
        u
            .set(
                author.firstName(),
                author.firstName().concat("*")
            )
            .where(
                author
                    .books()
                    .store()
                    .name()
                    .eq("MANNING")
            );
    })
    .execute();
System.out.println("Affected row count: " + affectedRowCount);
```

:::note
和MySql不同，在Postgres中使用update语句的JOIN存在如下限制

1. 只能在`where`子句中使用表连接，不能在`set`子句中使用表连接。即Postgres还是只允许修改当前表的字段，支持连接到其它表仅仅是为了做条件过滤。

2. 连接路径的可以具有多级，如`author.books().store()`，其中，`books()`是第1级，`store()`是第2级。

    第一级连接的类型必须是`inner join`。
:::

最终生成针对Postgres的SQL语句，如下：

```sql
update 
    AUTHOR tb_1_ 
set 
    FIRST_NAME = concat(tb_1_.FIRST_NAME, ?) 
from BOOK_AUTHOR_MAPPING as tb_2_ /* α */
inner join BOOK as tb_3_ /* β */
    on tb_2_.BOOK_ID = tb_3_.ID 
inner join BOOK_STORE as tb_4_ /* γ */
    on tb_3_.STORE_ID = tb_4_.ID 
where 
    tb_1_.ID = tb_2_.AUTHOR_ID /* join codition of α */
and 
    tb_4_.NAME = ?
```

:::note

连接路径`author.books().store()`有两级，`books()`是第1级，`store()`是第2级。

1. 第一级`books()`涉及两张表
    - `α`处的`BOOK_AUTHOR_MAPPING`表
    - `β`处的`BOOK`表

2. 第二级`store()`涉及一张表
    - `γ`处的`BOOK_STORE`表

在Postgres的update语句中，直接和主表相关的表连接不能使用`join` + `on`的写法，必须等价变换为`from` + `where`的写法。

这就是jimmer-sql规定Postgres方言下update语句第一级连接的类型必须是`inner join`的原因。
:::