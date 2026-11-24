<#--
// Copyright (c) Monoptic LLC.
// SPDX-License-Identifier: Apache-2.0
-->

<#--
  monoql's DDL, DCL and control statements.

  Rule of the house: nothing is parsed that is not carried into the parse tree
  and from there into a metadata row. If a clause would have no effect, it is
  not in the grammar.
-->

boolean IfNotExistsOpt() :
{
}
{
    <IF> <NOT> <EXISTS> { return true; }
|
    { return false; }
}

boolean IfExistsOpt() :
{
}
{
    <IF> <EXISTS> { return true; }
|
    { return false; }
}

/** Whether the next token is a bare word: an unquoted identifier, or any
 * keyword, reserved or not. */
JAVACODE boolean IsBareWord() {
    return getToken(1).image.matches("[A-Za-z_][A-Za-z0-9_$]*");
}

/** Consumes the next token and returns it as written. */
JAVACODE String AnyToken() {
    return getNextToken().image;
}

/** Appends one part of an option key: a bare word, exactly as written, or
 * the content of a quoted identifier.
 *
 * <p>Option keys are not SQL identifiers. They are case-sensitive, and words
 * like "user", "table" and "type" are perfectly good keys, though they are
 * reserved in SQL. */
void RawIdentifier(StringBuilder b) :
{
    final SqlIdentifier id;
    final String word;
}
{
    LOOKAHEAD({ IsBareWord() })
    word = AnyToken() { b.append(word); }
|
    id = SimpleIdentifier() { b.append(id.getSimple()); }
}

String OptionKey() :
{
    final SqlNode n;
    final StringBuilder b = new StringBuilder();
}
{
    n = StringLiteral() { return SqlClauses.text(n); }
|
    RawIdentifier(b) ( <DOT> { b.append('.'); } RawIdentifier(b) )*
    { return b.toString(); }
}

String OptionValue() :
{
    final SqlNode n;
}
{
    n = StringLiteral() { return SqlClauses.text(n); }
|
    n = NumericLiteral() { return SqlClauses.text(n); }
|
    <TRUE> { return "true"; }
|
    <FALSE> { return "false"; }
}

void KeyValue(Map<String, String> map) :
{
    final String key;
    final String value;
}
{
    key = OptionKey() [ <EQ> ] value = OptionValue() {
        if (map.put(key, value) != null) {
            throw new ParseException("Duplicate key '" + key + "'");
        }
    }
}

/** Parses "(key = value, ...)". Keys are identifiers or strings; "=" is
 * optional; values are literals. */
void KeyValueList(Map<String, String> map) :
{
}
{
    <LPAREN>
    [ KeyValue(map) ( <COMMA> KeyValue(map) )* ]
    <RPAREN>
}

/** Parses a partition specification, "(column = value, ...)". Unlike option
 * keys, the keys are column names, so they are identifiers. */
void PartitionSpec(Map<String, String> map) :
{
    SqlIdentifier id;
    String value;
}
{
    <LPAREN>
    id = SimpleIdentifier() <EQ> value = OptionValue() {
        map.put(id.getSimple(), value);
    }
    (
        <COMMA> id = SimpleIdentifier() <EQ> value = OptionValue() {
            if (map.put(id.getSimple(), value) != null) {
                throw new ParseException("Duplicate partition column '"
                    + id.getSimple() + "'");
            }
        }
    )*
    <RPAREN>
}

/** Parses an optional options clause. WITH, OPTIONS and TBLPROPERTIES are
 * interchangeable. */
Map<String, String> OptionsOpt() :
{
    final Map<String, String> map = new LinkedHashMap<String, String>();
}
{
    [ ( <WITH> | <OPTIONS> | <TBLPROPERTIES> ) KeyValueList(map) ]
    { return map; }
}

String CommentOpt() :
{
    final SqlNode n;
}
{
    <COMMENT> [ <EQ> ] n = StringLiteral() { return SqlClauses.text(n); }
|
    { return null; }
}

String ScheduleOpt() :
{
    final SqlNode n;
}
{
    <SCHEDULE> [ <EQ> ] n = StringLiteral() { return SqlClauses.text(n); }
|
    { return null; }
}

/** Kinds of object that can be dropped or commented on. */
ObjectKind AnyObjectKind() :
{
}
{
    <CATALOG> { return ObjectKind.CATALOG; }
|   ( <SCHEMA> | <DATABASE> ) { return ObjectKind.SCHEMA; }
|   <TABLE> { return ObjectKind.TABLE; }
|   <VIEW> { return ObjectKind.VIEW; }
|   <MATERIALIZED> <VIEW> { return ObjectKind.MATERIALIZED_VIEW; }
|   <FUNCTION> { return ObjectKind.FUNCTION; }
|   <TRIGGER> { return ObjectKind.TRIGGER; }
|   <ROLE> { return ObjectKind.ROLE; }
|   <USER> { return ObjectKind.USER; }
}

/** Kind of object in the ON clause of GRANT, REVOKE and SHOW GRANTS. Defaults
 * to TABLE. The lookahead lets a table be called, say, "schema". */
ObjectKind SecurableKindOpt() :
{
}
{
    LOOKAHEAD(<CATALOG> SimpleIdentifier())
    <CATALOG> { return ObjectKind.CATALOG; }
|   LOOKAHEAD(( <SCHEMA> | <DATABASE> ) SimpleIdentifier())
    ( <SCHEMA> | <DATABASE> ) { return ObjectKind.SCHEMA; }
|   <TABLE> { return ObjectKind.TABLE; }
|   LOOKAHEAD(<VIEW> SimpleIdentifier())
    <VIEW> { return ObjectKind.VIEW; }
|   LOOKAHEAD(<MATERIALIZED> <VIEW>)
    <MATERIALIZED> <VIEW> { return ObjectKind.MATERIALIZED_VIEW; }
|   <FUNCTION> { return ObjectKind.FUNCTION; }
|   <TRIGGER> { return ObjectKind.TRIGGER; }
|   { return ObjectKind.TABLE; }
}

SqlColumnDecl ColumnDecl(List<SqlNode> primaryKey) :
{
    final SqlIdentifier id;
    final SqlDataTypeSpec type;
    boolean nullable = true;
    final String comment;
}
{
    id = SimpleIdentifier()
    type = DataType()
    [
        <NOT> <NULL> { nullable = false; }
    |
        <NULL>
    ]
    [
        <PRIMARY> <KEY> {
            if (primaryKey == null) {
                throw new ParseException("PRIMARY KEY is not allowed here");
            }
            primaryKey.add(id);
        }
    ]
    comment = CommentOpt()
    {
        return new SqlColumnDecl(id, type, nullable, comment);
    }
}

void TableElement(List<SqlColumnDecl> columns, List<SqlNode> primaryKey) :
{
    final SqlNodeList list;
    final SqlColumnDecl column;
}
{
    <PRIMARY> <KEY> list = ParenthesizedSimpleIdentifierList() {
        primaryKey.addAll(list.getList());
    }
|
    column = ColumnDecl(primaryKey) { columns.add(column); }
}

SqlCreate SqlCreateCatalog(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    final String comment;
    final Map<String, String> opts;
}
{
    <CATALOG> ifNotExists = IfNotExistsOpt() id = SimpleIdentifier()
    comment = CommentOpt()
    opts = OptionsOpt()
    {
        return new SqlCreateCatalog(s.end(this), replace, ifNotExists, id,
            comment, opts);
    }
}

SqlCreate SqlCreateSchema(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    final String comment;
    final Map<String, String> opts;
}
{
    ( <SCHEMA> | <DATABASE> )
    ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    comment = CommentOpt()
    opts = OptionsOpt()
    {
        return new SqlCreateSchema(s.end(this), replace, ifNotExists, id,
            comment, opts);
    }
}

SqlCreate SqlCreateTable(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    final List<SqlColumnDecl> columns = new ArrayList<SqlColumnDecl>();
    final List<SqlNode> primaryKey = new ArrayList<SqlNode>();
    SqlNodeList partitionKeys = SqlNodeList.EMPTY;
    final String comment;
    final Map<String, String> opts;
    SqlNode query = null;
}
{
    <TABLE> ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    [
        <LPAREN>
        TableElement(columns, primaryKey)
        ( <COMMA> TableElement(columns, primaryKey) )*
        <RPAREN>
    ]
    [ <PARTITIONED> <BY> partitionKeys = ParenthesizedSimpleIdentifierList() ]
    comment = CommentOpt()
    opts = OptionsOpt()
    [ <AS> query = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY) ]
    {
        if (columns.isEmpty() == (query == null)) {
            throw new ParseException(
                "CREATE TABLE requires either a column list or AS query");
        }
        return new SqlCreateTable(s.end(this), replace, ifNotExists, id,
            columns, new SqlNodeList(primaryKey, getPos()), partitionKeys,
            comment, opts, query);
    }
}

SqlCreate SqlCreateView(Span s, boolean replace) :
{
    boolean materialized = false;
    final boolean ifNotExists;
    final SqlIdentifier id;
    SqlNodeList columnList = SqlNodeList.EMPTY;
    String schedule = null;
    boolean continuous = false;
    final String comment;
    Map<String, String> opts = new LinkedHashMap<String, String>();
    final SqlNode query;
    SqlNode n;
}
{
    [ <MATERIALIZED> { materialized = true; } ]
    <VIEW> ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    [ columnList = ParenthesizedSimpleIdentifierList() ]
    [
        <SCHEDULE> {
            if (!materialized) {
                throw new ParseException(
                    "SCHEDULE requires CREATE MATERIALIZED VIEW");
            }
        }
        [ <EQ> ] n = StringLiteral() { schedule = SqlClauses.text(n); }
    |
        <REFRESH> <CONTINUOUSLY> {
            if (!materialized) {
                throw new ParseException(
                    "REFRESH CONTINUOUSLY requires CREATE MATERIALIZED VIEW");
            }
            continuous = true;
        }
    ]
    comment = CommentOpt()
    [
        ( <WITH> | <OPTIONS> | <TBLPROPERTIES> ) {
            if (!materialized) {
                throw new ParseException(
                    "Options require CREATE MATERIALIZED VIEW");
            }
        }
        KeyValueList(opts)
    ]
    <AS> query = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
    {
        return new SqlCreateView(s.end(this), replace, ifNotExists,
            materialized, id, columnList, schedule, continuous, comment, opts,
            query);
    }
}

/** The name of a language. SQL is a reserved word, so it needs a case of
 * its own. */
String LanguageName() :
{
    final SqlIdentifier id;
    final SqlNode n;
}
{
    id = SimpleIdentifier() { return id.getSimple(); }
|
    <SQL> { return "SQL"; }
|
    n = StringLiteral() { return SqlClauses.text(n); }
}

SqlCreate SqlCreateFunction(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    String language = null;
    final String comment;
    final Map<String, String> opts;
    final SqlNode definition;
}
{
    <FUNCTION> ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    [ <LANGUAGE> language = LanguageName() ]
    comment = CommentOpt()
    opts = OptionsOpt()
    <AS> definition = StringLiteral()
    [
        <LANGUAGE> {
            if (language != null) {
                throw new ParseException("LANGUAGE specified twice");
            }
        }
        language = LanguageName()
    ]
    {
        return new SqlCreateFunction(s.end(this), replace, ifNotExists, id,
            language == null ? "JAVA" : language, comment,
            opts, SqlClauses.text(definition));
    }
}

void TriggerEvent(List<String> events) :
{
}
{
    ( <INSERT> | <UPDATE> | <DELETE> ) {
        events.add(token.image.toUpperCase(Locale.ROOT));
    }
}

/** Parses a statement and returns it as normalized SQL text. */
String StatementText() :
{
    final SqlNode stmt;
}
{
    stmt = SqlStmt() {
        return stmt.toSqlString(CalciteSqlDialect.DEFAULT).getSql();
    }
}

SqlCreate SqlCreateTrigger(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    String timing = null;
    final List<String> events = new ArrayList<String>();
    SqlIdentifier table = null;
    final String schedule;
    final String comment;
    final Map<String, String> opts;
    final String action;
    final SqlNode n;
}
{
    <TRIGGER> ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    [
        ( <BEFORE> { timing = "BEFORE"; } | <AFTER> { timing = "AFTER"; } )
        TriggerEvent(events) ( <OR> TriggerEvent(events) )*
    ]
    [ <ON> table = CompoundIdentifier() ]
    schedule = ScheduleOpt()
    comment = CommentOpt()
    opts = OptionsOpt()
    (
        <AS>
        (
            LOOKAHEAD(1)
            n = StringLiteral() { action = SqlClauses.text(n); }
        |
            action = StatementText()
        )
    |
        <EXECUTE> action = StatementText()
    )
    {
        if (timing != null && table == null) {
            throw new ParseException(timing + " " + String.join(" OR ", events)
                + " requires ON table");
        }
        return new SqlCreateTrigger(s.end(this), replace, ifNotExists, id,
            timing, events, table, schedule, comment, opts, action);
    }
}

SqlCreate SqlCreatePrincipal(Span s, boolean replace) :
{
    final ObjectKind kind;
    final boolean ifNotExists;
    final SqlIdentifier id;
    final String comment;
    final Map<String, String> opts;
}
{
    ( <ROLE> { kind = ObjectKind.ROLE; } | <USER> { kind = ObjectKind.USER; } )
    ifNotExists = IfNotExistsOpt() id = CompoundIdentifier()
    comment = CommentOpt()
    opts = OptionsOpt()
    {
        return new SqlCreatePrincipal(s.end(this), replace, ifNotExists, kind,
            id, comment, opts);
    }
}

/** Everything that can follow CREATE [OR REPLACE]. */
SqlCreate SqlCreateAny(Span s, boolean replace) :
{
    final SqlCreate create;
}
{
    (
        create = SqlCreateCatalog(s, replace)
    |   create = SqlCreateSchema(s, replace)
    |   create = SqlCreateTable(s, replace)
    |   create = SqlCreateView(s, replace)
    |   create = SqlCreateFunction(s, replace)
    |   create = SqlCreateTrigger(s, replace)
    |   create = SqlCreatePrincipal(s, replace)
    )
    { return create; }
}

/** Everything that can follow DROP. */
SqlDrop SqlDropAny(Span s, boolean replace) :
{
    final ObjectKind kind;
    final boolean ifExists;
    final SqlIdentifier id;
}
{
    kind = AnyObjectKind() ifExists = IfExistsOpt() id = CompoundIdentifier()
    {
        return new SqlDropObject(s.end(this), kind, ifExists, id);
    }
}

/** One word of a privilege, e.g. the CREATE or the TABLE of CREATE TABLE. */
void PrivilegeWord(StringBuilder b) :
{
    final SqlIdentifier id;
    final String word;
}
{
    (
        id = SimpleIdentifier() {
            word = id.getSimple();
        }
    |
        ( <SELECT> | <INSERT> | <UPDATE> | <DELETE> | <MERGE> | <TRUNCATE>
        | <CREATE> | <DROP> | <ALTER> | <EXECUTE> | <REFERENCES> | <TRIGGER>
        | <ALL> | <TABLE> | <FUNCTION> | <PROCEDURE> | <USER> | <GROUP> ) {
            word = token.image.toUpperCase(Locale.ROOT);
        }
    )
    {
        if (b.length() > 0) {
            b.append(' ');
        }
        b.append(word);
    }
}

/** A privilege (or, in a role grant, a role name). */
String Privilege() :
{
    final SqlNode n;
    final StringBuilder b = new StringBuilder();
}
{
    n = StringLiteral() { return SqlClauses.text(n); }
|
    ( PrivilegeWord(b) )+ {
        final String p = b.toString();
        return p.equals("ALL") ? "ALL PRIVILEGES" : p;
    }
}

String PrincipalName() :
{
    final SqlNode n;
    final SqlIdentifier id;
}
{
    n = StringLiteral() { return SqlClauses.text(n); }
|
    id = SimpleIdentifier() { return id.getSimple(); }
}

/** [USER | ROLE | GROUP] name. */
SqlGrant.Grantee Grantee() :
{
    String type = "PRINCIPAL";
    final String name;
}
{
    (
        <USER> { type = "USER"; } name = PrincipalName()
    |
        <GROUP> { type = "GROUP"; } name = PrincipalName()
    |
        LOOKAHEAD(<ROLE> PrincipalName())
        <ROLE> { type = "ROLE"; } name = PrincipalName()
    |
        name = PrincipalName()
    )
    {
        return new SqlGrant.Grantee(type, name);
    }
}

void Grantees(List<SqlGrant.Grantee> list) :
{
    SqlGrant.Grantee g;
}
{
    g = Grantee() { list.add(g); }
    ( <COMMA> g = Grantee() { list.add(g); } )*
}

/** GRANT and REVOKE, of privileges and of roles. */
SqlNode SqlGrantStmt() :
{
    final Span s;
    SqlGrant.Action action;
    boolean roleKeyword = false;
    final List<String> items = new ArrayList<String>();
    String item;
    ObjectKind kind = null;
    SqlIdentifier object = null;
    final List<SqlGrant.Grantee> grantees = new ArrayList<SqlGrant.Grantee>();
    boolean grantOption = false;
}
{
    (
        <GRANT> { s = span(); action = SqlGrant.Action.GRANT; }
    |
        <REVOKE> { s = span(); action = SqlGrant.Action.REVOKE; }
        [
            <GRANT> <OPTION> <FOR> {
                action = SqlGrant.Action.REVOKE_GRANT_OPTION;
            }
        ]
    )
    [ LOOKAHEAD(1) <ROLE> { roleKeyword = true; } ]
    item = Privilege() { items.add(item); }
    ( <COMMA> item = Privilege() { items.add(item); } )*
    [ <ON> kind = SecurableKindOpt() object = CompoundIdentifier() ]
    (
        <TO> {
            if (action != SqlGrant.Action.GRANT) {
                throw new ParseException("REVOKE requires FROM, not TO");
            }
        }
    |
        <FROM> {
            if (action == SqlGrant.Action.GRANT) {
                throw new ParseException("GRANT requires TO, not FROM");
            }
        }
    )
    Grantees(grantees)
    [ <WITH> <GRANT> <OPTION> { grantOption = true; } ]
    {
        if (object == null) {
            // GRANT [ROLE] r TO g
            if (action == SqlGrant.Action.REVOKE_GRANT_OPTION || grantOption) {
                throw new ParseException(
                    "GRANT OPTION applies to privileges, and requires ON");
            }
        } else if (roleKeyword) {
            throw new ParseException("GRANT ROLE does not take ON");
        }
        if (grantOption && action != SqlGrant.Action.GRANT) {
            throw new ParseException("WITH GRANT OPTION requires GRANT");
        }
        return new SqlGrant(s.end(this), action, items, kind, object,
            grantees, grantOption);
    }
}

/** PAUSE, RESUME, FIRE and REFRESH. */
SqlNode SqlControlStmt() :
{
    final Span s;
    final SqlControl.Verb verb;
    final ObjectKind kind;
    final SqlIdentifier id;
    Map<String, String> opts = new LinkedHashMap<String, String>();
}
{
    (
        (
            <PAUSE> { s = span(); verb = SqlControl.Verb.PAUSE; }
        |
            <RESUME> { s = span(); verb = SqlControl.Verb.RESUME; }
        )
        (
            <TRIGGER> { kind = ObjectKind.TRIGGER; }
        |
            <MATERIALIZED> <VIEW> { kind = ObjectKind.MATERIALIZED_VIEW; }
        )
        id = CompoundIdentifier()
    |
        <FIRE> { s = span(); verb = SqlControl.Verb.FIRE; }
        <TRIGGER> { kind = ObjectKind.TRIGGER; }
        id = CompoundIdentifier()
        opts = OptionsOpt()
    |
        <REFRESH> { s = span(); verb = SqlControl.Verb.REFRESH; }
        <MATERIALIZED> <VIEW> { kind = ObjectKind.MATERIALIZED_VIEW; }
        id = CompoundIdentifier()
    )
    {
        return new SqlControl(s.end(this), verb, kind, id, opts);
    }
}

SqlIdentifier InScope() :
{
    final SqlIdentifier id;
}
{
    ( <IN> | <FROM> ) id = CompoundIdentifier() { return id; }
}

SqlNode SqlShowStmt() :
{
    final Span s;
    final SqlShow.What what;
    SqlIdentifier scope = null;
    String like = null;
    ObjectKind kind = null;
    SqlGrant.Grantee grantee = null;
    SqlNode n;
}
{
    <SHOW> { s = span(); }
    (
        <GRANTS> { what = SqlShow.What.GRANTS; }
        [ <ON> kind = SecurableKindOpt() scope = CompoundIdentifier() ]
        [ <TO> grantee = Grantee() ]
    |
        (
            <CATALOGS> { what = SqlShow.What.CATALOGS; }
        |
            <SETTINGS> { what = SqlShow.What.SETTINGS; }
        |
            ( <SCHEMAS> | <DATABASES> ) { what = SqlShow.What.SCHEMAS; }
            [ scope = InScope() ]
        |
            <TABLES> { what = SqlShow.What.TABLES; }
            [ scope = InScope() ]
        |
            <VIEWS> { what = SqlShow.What.VIEWS; }
            [ scope = InScope() ]
        |
            <MATERIALIZED> <VIEWS> { what = SqlShow.What.MATERIALIZED_VIEWS; }
            [ scope = InScope() ]
        |
            <FUNCTIONS> { what = SqlShow.What.FUNCTIONS; }
            [ scope = InScope() ]
        |
            <TRIGGERS> { what = SqlShow.What.TRIGGERS; }
            [ scope = InScope() ]
        |
            <ROLES> { what = SqlShow.What.ROLES; }
            [ scope = InScope() ]
        |
            <USERS> { what = SqlShow.What.USERS; }
            [ scope = InScope() ]
        |
            <COLUMNS> { what = SqlShow.What.COLUMNS; }
            scope = InScope()
        |
            <PARTITIONS> { what = SqlShow.What.PARTITIONS; }
            scope = InScope()
        )
        [ <LIKE> n = StringLiteral() { like = SqlClauses.text(n); } ]
    )
    {
        return new SqlShow(s.end(this), what, scope, like, kind, grantee);
    }
}

SqlNode SqlUseStmt() :
{
    final Span s;
    ObjectKind kind = null;
    final SqlIdentifier id;
}
{
    <USE> { s = span(); }
    (
        LOOKAHEAD(<CATALOG> SimpleIdentifier())
        <CATALOG> { kind = ObjectKind.CATALOG; } id = SimpleIdentifier()
    |
        LOOKAHEAD(( <SCHEMA> | <DATABASE> ) SimpleIdentifier())
        ( <SCHEMA> | <DATABASE> ) { kind = ObjectKind.SCHEMA; }
        id = CompoundIdentifier()
    |
        id = CompoundIdentifier()
    )
    {
        return new SqlUse(s.end(this), kind, id);
    }
}

SqlNode SqlAlterTableStmt() :
{
    final Span s;
    final SqlIdentifier id;
    final SqlAlterTable.Action action;
    SqlColumnDecl column = null;
    SqlIdentifier target = null;
    final Map<String, String> partition = new LinkedHashMap<String, String>();
    Map<String, String> opts = new LinkedHashMap<String, String>();
    boolean lenient = false;
}
{
    <ALTER> { s = span(); } <TABLE> id = CompoundIdentifier()
    (
        <ADD>
        (
            <COLUMN> column = ColumnDecl(null) {
                action = SqlAlterTable.Action.ADD_COLUMN;
            }
        |
            lenient = IfNotExistsOpt() <PARTITION> PartitionSpec(partition)
            opts = OptionsOpt() {
                action = SqlAlterTable.Action.ADD_PARTITION;
            }
        )
    |
        <DROP>
        (
            <COLUMN> target = SimpleIdentifier() {
                action = SqlAlterTable.Action.DROP_COLUMN;
            }
        |
            lenient = IfExistsOpt() <PARTITION> PartitionSpec(partition) {
                action = SqlAlterTable.Action.DROP_PARTITION;
            }
        )
    |
        <RENAME> <TO> target = CompoundIdentifier() {
            action = SqlAlterTable.Action.RENAME;
        }
    |
        <SET> [ <OPTIONS> | <TBLPROPERTIES> ] KeyValueList(opts) {
            action = SqlAlterTable.Action.SET_OPTIONS;
        }
    )
    {
        return new SqlAlterTable(s.end(this), id, action, column, target,
            partition, opts, lenient);
    }
}

SqlNode SqlCommentOnStmt() :
{
    final Span s;
    final ObjectKind kind;
    final SqlIdentifier id;
    String comment = null;
    SqlNode n;
}
{
    <COMMENT> { s = span(); } <ON>
    ( kind = AnyObjectKind() | <COLUMN> { kind = ObjectKind.COLUMN; } )
    id = CompoundIdentifier()
    <IS>
    (
        n = StringLiteral() { comment = SqlClauses.text(n); }
    |
        <NULL>
    )
    {
        return new SqlCommentOn(s.end(this), kind, id, comment);
    }
}
