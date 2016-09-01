/**********************************************************************
Copyright (c) 2008 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.query.NullOrderingType;
import org.datanucleus.store.rdbms.identifier.DatastoreIdentifier;
import org.datanucleus.store.rdbms.mapping.datastore.DatastoreMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.RDBMSPropertyNames;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.adapter.DatastoreAdapter;
import org.datanucleus.store.rdbms.query.QueryGenerator;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.expression.AggregateExpression;
import org.datanucleus.store.rdbms.sql.expression.BooleanExpression;
import org.datanucleus.store.rdbms.sql.expression.BooleanLiteral;
import org.datanucleus.store.rdbms.sql.expression.BooleanSubqueryExpression;
import org.datanucleus.store.rdbms.sql.expression.ResultAliasExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.Column;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Class providing an API for generating SQL statements.
 * Caller should create the SQLStatement object and (optionally) call setClassLoaderResolver() to set any
 * class loading restriction. Then the caller builds up the statement using the various methods, and 
 * accesses the SQL statement using getStatement(). Generated statement is of the form :-
 * <pre>
 * SELECT {expr}, {expr}, ...
 * FROM {tblExpr} [joinInfo {tblExpr} ON ...] ...
 * WHERE {boolExpr} [AND|OR] {boolExpr} ...
 * GROUP BY {expr}, {expr}
 * HAVING {boolExpr}
 * ORDER BY {expr} [ASC|DESC], {expr} [ASC|DESC], ...
 * </pre>
 * and also supports UNIONs between SQLStatements, and having sub-queries of other SQLStatements.
 * Alternatively, for an UPDATE,
 * <pre>
 * UPDATE {tbl}
 * SET {expr}={val}, {expr}={val}, ...
 * WHERE {boolExpr} [AND|OR] {boolExpr} ...
 * </pre>
 * <p>
 * The generated SQL is cached. Any use of a mutating method, changing the composition of the statement
 * will clear the cached SQL, and it will be regenerated when <pre>getStatement</pre> is called next.
 * <h3>Table Groups</h3>
 * When tables are registered in the statement they are split into "table groups". A table group is,
 * in simple terms, an object in the query. If a table has a super-table and a field of the object
 * is selected that is in the super-table then the super-table is added to the table group. If there
 * is a join to a related object then the table of this object will be put in a new table group.
 * So the same datastore table can appear multiple times in the statement, each time for a different
 * object.
 * <h3>Table Aliases</h3>
 * All methods that cause a new SQLTable to be created also allow specification of the table alias
 * in the statement. Where the alias is not provided then we use a table "namer" (definable on the
 * plugin-point "org.datanucleus.store.rdbms.sql_tablenamer"). The table namer can define names
 * simply based on the table number, or based on table group and the number of tables in the group
 * etc etc. To select a particular table "namer", set the extension "datanucleus.sqlTableNamingStrategy"
 * to the key of the namer plugin. The default is "alpha-scheme" which bases table names on the
 * group and number in that group.
 * 
 * <b>Note that this class is not intended to be thread-safe. It is used by a single ExecutionContext</b>
 */
public class SQLStatement
{
    public static final String EXTENSION_SQL_TABLE_NAMING_STRATEGY = "table-naming-strategy";

    /** Map of SQLTable naming instance keyed by the name of the naming scheme. */
    protected static final Map<String, SQLTableNamer> tableNamerByName = new ConcurrentHashMap<String, SQLTableNamer>();

    /** Cached SQL statement, generated by getStatement(). */
    protected SQLText sql = null;

    /** Manager for the RDBMS datastore. */
    protected RDBMSStoreManager rdbmsMgr;

    /** ClassLoader resolver to use. Used by sub-expressions. Defaults to the loader resolver for the store manager. */
    protected ClassLoaderResolver clr;

    /** Context of any query generation. */
    protected QueryGenerator queryGenerator = null;

    protected SQLTableNamer namer = null;

    /** Name of class that this statement selects (optional, only typically for unioned statements). */
    protected String candidateClassName = null;

    /** Whether the statement is distinct. */
    protected boolean distinct = false;

    /** Map of extensions for use in generating the SQL, keyed by the extension name. */
    protected Map<String, Object> extensions;

    /** Parent statement, if this is a subquery. Must be set at construction. */
    protected SQLStatement parent = null;

    /** List of unioned SQLStatements (if any). */
    protected List<SQLStatement> unions = null;

    /** List of select objects. */
    protected List<SelectedItem> selectedItems = new ArrayList();

    /** Array of update expressions when the statement is an UPDATE. */
    protected SQLExpression[] updates = null;

    /** whether there is an aggregate expression present in the select **/
    protected boolean aggregated = false;

    /** Primary table for this statement. */
    protected SQLTable primaryTable;

    /** List of joins for this statement. */
    protected List<SQLJoin> joins;

    protected boolean requiresJoinReorder = false;

    /** Map of tables referenced in this statement, keyed by their alias. */
    protected Map<String, SQLTable> tables;

    /** Map of table groups keyed by the group name. */
    protected Map<String, SQLTableGroup> tableGroups = new HashMap<String, SQLTableGroup>();

    /** Where clause. */
    protected BooleanExpression where;

    /** Expression(s) for the GROUP BY clause. */
    protected List<SQLExpression> groupingExpressions = null;

    /** Having clause. */
    protected BooleanExpression having;

    /** Expressions for any ORDER BY clause. */
    protected SQLExpression[] orderingExpressions = null;

    /** Directions for any ORDER BY expressions (1 for each orderingExpressions entry). */
    protected boolean[] orderingDirections = null;

    /** Directives for null handling of any ORDER BY expressions (1 for each orderingExpressions entry). */
    protected NullOrderingType[] orderNullDirectives = null;

    /** The offset for any range restriction. */
    protected long rangeOffset = -1;

    /** The number of records to be retrieved in any range restriction. */
    protected long rangeCount = -1;

    protected class SelectedItem
    {
        SQLText sqlText;
        String alias;
        boolean primary = true;
        public SelectedItem(SQLText st, String alias, boolean primary)
        {
            this.sqlText = st;
            this.alias = alias;
            this.primary = primary;
        }
        public SQLText getSQLText()
        {
            return sqlText;
        }
        public String getAlias()
        {
            return alias;
        }
        public boolean isPrimary()
        {
            return primary;
        }
        public int hashCode()
        {
            return sqlText.hashCode() ^ (alias != null ? alias.hashCode() : 0);
        }
        public boolean equals(Object other)
        {
            if (other == null || !(other instanceof SelectedItem))
            {
                return false;
            }
            SelectedItem otherItem = (SelectedItem)other;
            if (!sqlText.equals(otherItem.sqlText))
            {
                return false;
            }
            if ((alias != null && !alias.equals(otherItem.alias)) || (otherItem.alias != null && !otherItem.alias.equals(alias)))
            {
                return false;
            }
            return true;
        }
    }

    /**
     * Constructor for an SQL statement.
     * @param rdbmsMgr The datastore manager
     * @param table The primary table
     * @param alias Alias for this table
     * @param tableGroupName Name of candidate table-group (if any). Uses "Group0" if not provided
     */
    public SQLStatement(RDBMSStoreManager rdbmsMgr, Table table, DatastoreIdentifier alias, String tableGroupName)
    {
        this(null, rdbmsMgr, table, alias, tableGroupName, null);
    }

    /**
     * Constructor for an SQL statement.
     * @param rdbmsMgr The datastore manager
     * @param table The primary table
     * @param alias Alias for this table
     * @param tableGroupName Name of candidate table-group (if any). Uses "Group0" if not provided
     * @param extensions Optional extensions
     */
    public SQLStatement(RDBMSStoreManager rdbmsMgr, Table table, DatastoreIdentifier alias, String tableGroupName, Map<String, Object> extensions)
    {
        this(null, rdbmsMgr, table, alias, tableGroupName, extensions);
    }

    /**
     * Constructor for an SQL statement that is a subquery of another statement.
     * @param parentStmt Parent statement
     * @param rdbmsMgr The datastore manager
     * @param table The primary table
     * @param alias Alias for this table
     * @param tableGroupName Name of candidate table-group (if any). Uses "Group0" if not provided
     */
    public SQLStatement(SQLStatement parentStmt, RDBMSStoreManager rdbmsMgr, Table table, DatastoreIdentifier alias, String tableGroupName)
    {
        this(parentStmt, rdbmsMgr, table, alias, tableGroupName, null);
    }

    /**
     * Constructor for an SQL statement that is a subquery of another statement.
     * @param parentStmt Parent statement
     * @param rdbmsMgr The datastore manager
     * @param table The primary table
     * @param alias Alias for this table
     * @param tableGroupName Name of candidate table-group (if any). Uses "Group0" if not provided
     * @param extensions Optional extensions
     */
    public SQLStatement(SQLStatement parentStmt, RDBMSStoreManager rdbmsMgr, Table table, DatastoreIdentifier alias, String tableGroupName, Map<String, Object> extensions)
    {
        this.parent = parentStmt;
        this.rdbmsMgr = rdbmsMgr;

        // Set the namer, using any override extension, otherwise the RDBMS default
        String namingStrategy = rdbmsMgr.getStringProperty(RDBMSPropertyNames.PROPERTY_RDBMS_SQL_TABLE_NAMING_STRATEGY);
        if (extensions != null && extensions.containsKey(EXTENSION_SQL_TABLE_NAMING_STRATEGY))
        {
            namingStrategy = (String) extensions.get(EXTENSION_SQL_TABLE_NAMING_STRATEGY);
        }
        namer = getTableNamer(namingStrategy);

        String tableGrpName = (tableGroupName != null ? tableGroupName : "Group0");
        if (alias == null)
        {
            // No alias provided so generate one
            alias = rdbmsMgr.getIdentifierFactory().newTableIdentifier(namer.getAliasForTable(this, table, tableGrpName));
        }
        this.primaryTable = new SQLTable(this, table, alias, tableGrpName);
        putSQLTableInGroup(primaryTable, tableGrpName, null);

        if (parentStmt != null)
        {
            // Use same query generator
            queryGenerator = parentStmt.getQueryGenerator();
        }
    }

    public RDBMSStoreManager getRDBMSManager()
    {
        return rdbmsMgr;
    }

    public void setClassLoaderResolver(ClassLoaderResolver clr)
    {
        this.clr = clr;
    }

    public ClassLoaderResolver getClassLoaderResolver()
    {
        if (clr == null)
        {
            clr = rdbmsMgr.getNucleusContext().getClassLoaderResolver(null);
        }
        return clr;
    }

    public void setCandidateClassName(String name)
    {
        this.candidateClassName = name;
    }

    public String getCandidateClassName()
    {
        return candidateClassName;
    }

    public QueryGenerator getQueryGenerator()
    {
        return queryGenerator;
    }

    public void setQueryGenerator(QueryGenerator gen)
    {
        this.queryGenerator = gen;
    }

    public SQLExpressionFactory getSQLExpressionFactory()
    {
        return rdbmsMgr.getSQLExpressionFactory();
    }

    public DatastoreAdapter getDatastoreAdapter()
    {
        return rdbmsMgr.getDatastoreAdapter();
    }

    public SQLStatement getParentStatement()
    {
        return parent;
    }

    /**
     * Convenience method to return if this statement is a child (inner) statement of the supplied
     * statement.
     * @param stmt The statement that may be parent, grandparent etc of this statement
     * @return Whether this is a child of the supplied statement
     */
    public boolean isChildStatementOf(SQLStatement stmt)
    {
        if (stmt == null || parent == null)
        {
            return false;
        }

        if (stmt == parent)
        {
            return true;
        }
        return isChildStatementOf(parent);
    }

    /**
     * Method to define an extension for this query statement allowing control over its behaviour in generating a query.
     * @param key Extension key
     * @param value Value for the key
     */
    public void addExtension(String key, Object value)
    {
        if (key == null)
        {
            return;
        }
        invalidateStatement();

        if (key.equals(EXTENSION_SQL_TABLE_NAMING_STRATEGY))
        {
            namer = getTableNamer((String) value);
            return;
        }

        if (extensions == null)
        {
            extensions = new HashMap();
        }
        extensions.put(key, value);
    }

    /**
     * Accessor for the value for an extension.
     * @param key Key for the extension
     * @return Value for the extension (if any)
     */
    public Object getValueForExtension(String key)
    {
        if (extensions == null)
        {
            return extensions;
        }
        return extensions.get(key);
    }

    /**
     * Method to union this SQL statement with another SQL statement.
     * @param stmt The other SQL statement to union
     */
    public void union(SQLStatement stmt)
    {
        invalidateStatement();
        if (unions == null)
        {
            unions = new ArrayList<SQLStatement>();
        }
        unions.add(stmt);
    }

    public int getNumberOfUnions()
    {
        if (unions == null)
        {
            return 0;
        }

        int number = unions.size();
        Iterator<SQLStatement> unionIterator = unions.iterator();
        while (unionIterator.hasNext())
        {
            SQLStatement unioned = unionIterator.next();
            number += unioned.getNumberOfUnions();
        }
        return number;
    }

    /**
     * Accessor for the unioned statements.
     * @return The unioned SQLStatements
     */
    public List<SQLStatement> getUnions()
    {
        return unions;
    }

    /**
     * Convenience accessor for whether all unions of this statement are for the same primary table.
     * @return Whether all unions have the same primary table
     */
    public boolean allUnionsForSamePrimaryTable()
    {
        if (unions != null)
        {
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement unionStmt = unionIter.next();
                if (!unionStmt.getPrimaryTable().equals(primaryTable))
                {
                    return false;
                }
            }
        }
        return true;
    }

    // --------------------------------- SELECT --------------------------------------

    /**
     * Accessor for whether the statement restricts the results to distinct.
     * @return Whether results are distinct
     */
    public boolean isDistinct()
    {
        return this.distinct;
    }

    /**
     * Mutator for whether the query returns distinct results.
     * @param distinct Whether to return distinct
     */
    public void setDistinct(boolean distinct)
    {
        invalidateStatement();
        this.distinct = distinct;
    }

    /**
     * Accessor for the number of selected items in the SELECT clause.
     * @return Number of selected items
     */
    public int getNumberOfSelects()
    {
        return selectedItems.size();
    }

    /**
     * Select an expression.
     * This will be used when adding aggregates to the select clause (e.g "COUNT(*)").
     * @param expr The expression to add to the select statement 
     * @param alias Optional alias for this selected expression
     * @return The index(es) of the expression in the select
     */
    public int[] select(SQLExpression expr, String alias)
    {
        if (expr == null)
        {
            throw new NucleusException("Expression to select is null");
        }

        invalidateStatement();

        boolean primary = true;
        if (expr instanceof AggregateExpression)
        {
            aggregated = true;
            primary = false;
        }
        else if (expr.getSQLTable() == null || expr.getJavaTypeMapping() == null)
        {
            primary = false;
        }

        int[] selected = new int[expr.getNumberOfSubExpressions()];
        if (expr.getNumberOfSubExpressions() > 1)
        {
            for (int i=0;i<expr.getNumberOfSubExpressions();i++)
            {
                selected[i] = selectItem(expr.getSubExpression(i).toSQLText(), alias != null ? (alias + i) : null, primary);
            }
        }
        else
        {
            selected[0] = selectItem(expr.toSQLText(), alias, primary);
        }

        if (unions != null)
        {
            // Apply the select to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.select(expr, alias);
            }
        }

        return selected;
    }

    /**
     * Add a select clause for the specified field (via its mapping).
     * If an alias is supplied and there are more than 1 column for this mapping then they will have
     * names like "{alias}_n" where n is the column number (starting at 0).
     * @param table The SQLTable to select from (null implies the primary table)
     * @param mapping The mapping for the field
     * @param alias optional alias
     * @param applyToUnions Whether to apply to unions
     * @return The column index(es) in the statement for the specified field (1 is first).
     */
    public int[] select(SQLTable table, JavaTypeMapping mapping, String alias, boolean applyToUnions)
    {
        if (mapping == null)
        {
            throw new NucleusException("Mapping to select is null");
        }
        else if (table == null)
        {
            // Default to the primary table if not specified
            table = primaryTable;
        }
        if (mapping.getTable() != table.getTable())
        {
            throw new NucleusException("Table being selected from (\"" + table.getTable() + 
                "\") is inconsistent with the column selected (\"" + mapping.getTable() + "\")");
        }

        invalidateStatement();

        DatastoreMapping[] mappings = mapping.getDatastoreMappings();
        int[] selected = new int[mappings.length];
        for (int i=0;i<selected.length;i++)
        {
            DatastoreIdentifier colAlias = null;
            if (alias != null)
            {
                String name = (selected.length > 1) ? (alias + "_" + i) : alias;
                colAlias = rdbmsMgr.getIdentifierFactory().newColumnIdentifier(name);
            }

            SQLColumn col = new SQLColumn(table, mappings[i].getColumn(), colAlias);
            selected[i] = selectItem(new SQLText(col.getColumnSelectString()), alias != null ? colAlias.toString() : null, true);
        }

        if (applyToUnions && unions != null)
        {
            // Apply the select to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.select(table, mapping, alias);
            }
        }

        return selected;
    }

    /**
     * Add a select clause for the specified field (via its mapping) and apply to unions.
     * If an alias is supplied and there are more than 1 column for this mapping then they will have
     * names like "{alias}_n" where n is the column number (starting at 0).
     * @param table The SQLTable to select from (null implies the primary table)
     * @param mapping The mapping for the field
     * @param alias optional alias
     * @return The column index(es) in the statement for the specified field (1 is first).
     */
    public int[] select(SQLTable table, JavaTypeMapping mapping, String alias)
    {
        return select(table, mapping, alias, true);
    }

    /**
     * Add a select clause for the specified column.
     * @param table The SQLTable to select from (null implies the primary table)
     * @param column The column
     * @param alias Optional alias
     * @return The column index in the statement for the specified column (1 is first).
     */
    public int select(SQLTable table, Column column, String alias)
    {
        if (column == null)
        {
            throw new NucleusException("Column to select is null");
        }
        else if (table == null)
        {
            // Default to the primary table if not specified
            table = primaryTable;
        }
        if (column.getTable() != table.getTable())
        {
            throw new NucleusException("Table being selected from (\"" + table.getTable() + 
                "\") is inconsistent with the column selected (\"" + column.getTable() + "\")");
        }

        invalidateStatement();

        DatastoreIdentifier colAlias = null;
        if (alias != null)
        {
            colAlias = rdbmsMgr.getIdentifierFactory().newColumnIdentifier(alias);
        }
        SQLColumn col = new SQLColumn(table, column, colAlias);
        int position = selectItem(new SQLText(col.getColumnSelectString()), alias != null ? colAlias.toString() : null, true);

        if (unions != null)
        {
            // Apply the select to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.select(table, column, alias);
            }
        }

        return position;
    }

    /**
     * Internal method to find the position of an item in the select list and return the position
     * if found (first position is 1). If the item is not found then it is added and the new position returned.
     * @param st SQLText to select
     * @param alias Optional alias
     * @param primary Whether this is a primary select (column)
     * @return Position in the select list (first position is 1)
     */
    private int selectItem(SQLText st, String alias, boolean primary)
    {
        SelectedItem item = new SelectedItem(st, alias, primary);
        if (selectedItems.contains(item))
        {
            // Already have a select item with this exact name so just return with that
            return selectedItems.indexOf(item) + 1;
        }

        int numberSelected = selectedItems.size();
        for (int i=0;i<numberSelected;i++)
        {
            SelectedItem selectedItem = selectedItems.get(i);
            if (selectedItem.getSQLText().equals(st))
            {
                // We already have the same column but different alias
                return (i+1);
            }
        }

        // The item doesn't exist so add it and return its new position
        selectedItems.add(item);
        return selectedItems.indexOf(item) + 1;
    }

    // --------------------------------- UPDATE --------------------------------------

    /**
     * Method to set the UPDATE clause of the statement.
     * @param exprs The update clause expression
     */
    public void setUpdates(SQLExpression[] exprs)
    {
        invalidateStatement();

        updates = exprs;
    }

    public boolean hasUpdates()
    {
        if (updates == null)
        {
            return false;
        }

        for (int i=0;i<updates.length;i++)
        {
            if (updates[i] != null)
            {
                return true;
            }
        }
        return false;
    }

    // --------------------------------- FROM --------------------------------------

    /**
     * Accessor for the primary table of the statement.
     * @return The primary table
     */
    public SQLTable getPrimaryTable()
    {
        return primaryTable;
    }

    /**
     * Accessor for the SQLTable object with the specified alias (if defined for this statement).
     * @param alias Alias
     * @return The SQLTable
     */
    public SQLTable getTable(String alias)
    {
        if (alias.equals(primaryTable.alias.getName()))
        {
            return primaryTable;
        }
        else if (tables != null)
        {
            return tables.get(alias);
        }
        return null;
    }

    /**
     * Convenience method to find a registered SQLTable that is for the specified table
     * @param table The table
     * @return The SQLTable (or null if not referenced)
     */
    public SQLTable getTableForDatastoreContainer(Table table)
    {
        for (SQLTableGroup grp : tableGroups.values())
        {
            SQLTable[] tbls = grp.getTables();
            for (int i=0;i<tbls.length;i++)
            {
                if (tbls[i].getTable() == table)
                {
                    return tbls[i];
                }
            }
        }
        return null;
    }

    /**
     * Accessor for the SQLTable object for the specified table (if defined for this statement)
     * in the specified table group.
     * @param table The table
     * @param groupName Name of the table group where we should look for this table
     * @return The SQLTable (if found)
     */
    public SQLTable getTable(Table table, String groupName)
    {
        if (groupName == null)
        {
            return null;
        }

        SQLTableGroup tableGrp = tableGroups.get(groupName);
        if (tableGrp == null)
        {
            return null;
        }
        SQLTable[] tables = tableGrp.getTables();
        for (int i=0;i<tables.length;i++)
        {
            if (tables[i].getTable() == table)
            {
                return tables[i];
            }
        }
        return null;
    }

    /**
     * Accessor for the table group with this name.
     * @param groupName Name of the group
     * @return The table group
     */
    public SQLTableGroup getTableGroup(String groupName)
    {
        return tableGroups.get(groupName);
    }

    /**
     * Accessor for the number of table groups.
     * @return Number of table groups (including that of the candidate)
     */
    public int getNumberOfTableGroups()
    {
        return tableGroups.size();
    }

    /**
     * Accessor for the number of tables defined for this statement.
     * @return Number of tables (in addition to the primary table)
     */
    public int getNumberOfTables()
    {
        return tables != null ? tables.size() : -1;
    }

    /**
     * Method to form a join to the specified table using the provided mappings, with the join also being applied to any UNIONed statements.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param applyToUnions Whether to apply to any unioned statements
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName, boolean applyToUnions)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, applyToUnions);
    }

    /**
     * Method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param applyToUnions Whether to apply to any unioned statements
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping, Object[] discrimValues, String tableGrpName, boolean applyToUnions)
    {
        invalidateStatement();

        // Create the SQLTable to join to.
        if (tables == null)
        {
            tables = new HashMap();
        }
        if (tableGrpName == null)
        {
            tableGrpName = "Group" + tableGroups.size();
        }
        if (targetAlias == null)
        {
            targetAlias = namer.getAliasForTable(this, target, tableGrpName);
        }
        if (sourceTable == null)
        {
            sourceTable = primaryTable;
        }
        DatastoreIdentifier targetId = rdbmsMgr.getIdentifierFactory().newTableIdentifier(targetAlias);
        SQLTable targetTbl = new SQLTable(this, target, targetId, tableGrpName);
        putSQLTableInGroup(targetTbl, tableGrpName, joinType);

        addJoin(joinType, sourceTable, sourceMapping, sourceParentMapping, targetTbl, targetMapping, targetParentMapping, discrimValues);

        if (unions != null && applyToUnions)
        {
            // Apply the join to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.join(joinType, sourceTable, sourceMapping, sourceParentMapping, target, targetAlias, targetMapping, targetParentMapping, discrimValues, tableGrpName, true);
            }
        }

        return targetTbl;
    }

    /**
     * Method to form an inner join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable innerJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.INNER_JOIN, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form an inner join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable innerJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.INNER_JOIN, sourceTable, sourceMapping, sourceParentMapping, target, targetAlias, targetMapping, targetParentMapping, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a left outer join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable leftOuterJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.LEFT_OUTER_JOIN, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a left outer join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable leftOuterJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.LEFT_OUTER_JOIN, sourceTable, sourceMapping, sourceParentMapping, target, targetAlias, targetMapping, targetParentMapping, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a right outer join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable rightOuterJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.RIGHT_OUTER_JOIN, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a right outer join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping mapping for the parent of the source
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping mapping for the parent of the target
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable rightOuterJoin(SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(JoinType.RIGHT_OUTER_JOIN, sourceTable, sourceMapping, sourceParentMapping, target, targetAlias, targetMapping, targetParentMapping, discrimValues, tableGrpName, true);
    }

    /**
     * Method to form a right outer join to the specified table using the provided mappings.
     * Will be applied to all unioned statements.
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable crossJoin(Table target, String targetAlias, String tableGrpName)
    {
        invalidateStatement();

        // Create the SQLTable to join to.
        if (tables == null)
        {
            tables = new HashMap();
        }
        if (tableGrpName == null)
        {
            tableGrpName = "Group" + tableGroups.size();
        }
        if (targetAlias == null)
        {
            targetAlias = namer.getAliasForTable(this, target, tableGrpName);
        }
        DatastoreIdentifier targetId = rdbmsMgr.getIdentifierFactory().newTableIdentifier(targetAlias);
        SQLTable targetTbl = new SQLTable(this, target, targetId, tableGrpName);
        putSQLTableInGroup(targetTbl, tableGrpName, JoinType.CROSS_JOIN);

        addJoin(JoinType.CROSS_JOIN, primaryTable, null, null, targetTbl, null, null, null);

        if (unions != null)
        {
            // Apply the join to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.crossJoin(target, targetAlias, tableGrpName);
            }
        }

        return targetTbl;
    }

    /**
     * Accessor for the type of join used for the specified table.
     * @param sqlTbl The table to check
     * @return The join type, or null if not joined in this statement
     */
    public JoinType getJoinTypeForTable(SQLTable sqlTbl)
    {
        if (joins == null)
        {
            return null;
        }
        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTable().equals(sqlTbl))
            {
                return join.getType();
            }
        }
        return null;
    }

    /**
     * Accessor for the type of join used for the specified table.
     * @param sqlTbl The table to check
     * @return The join type, or null if not joined in this statement
     */
    public SQLJoin getJoinForTable(SQLTable sqlTbl)
    {
        if (joins == null)
        {
            return null;
        }
        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTable().equals(sqlTbl))
            {
                return join;
            }
        }
        return null;
    }

    /**
     * Method to remove a cross join for the specified table (if joined via cross join).
     * Also removes the table from the list of tables.
     * This is called where we have bound a variable via a CROSS JOIN (in the absence of better information)
     * and found out later it could become an INNER JOIN.
     * If the supplied table is not joined via a cross join then does nothing.
     * @param targetSqlTbl The table to drop the cross join for
     * @return The removed alias
     */
    public String removeCrossJoin(SQLTable targetSqlTbl)
    {
        if (joins == null)
        {
            return null;
        }

        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTable().equals(targetSqlTbl) && join.getType() == JoinType.CROSS_JOIN)
            {
                joinIter.remove();
                requiresJoinReorder = true;
                tables.remove(join.getTable().alias.getName());
                String removedAliasName = join.getTable().alias.getName();

                if (unions != null)
                {
                    // Apply the join removal to all unions
                    Iterator<SQLStatement> unionIter = unions.iterator();
                    while (unionIter.hasNext())
                    {
                        SQLStatement stmt = unionIter.next();
                        stmt.removeCrossJoin(targetSqlTbl);
                    }
                }

                return removedAliasName;
            }
        }

        return null;
    }

    /**
     * Convenience method to add the SQLTable to the specified group.
     * If the group doesn't yet exist then it adds it.
     * @param sqlTbl SQLTable to add
     * @param groupName The group
     * @param joinType type of join to start this table group
     */
    private void putSQLTableInGroup(SQLTable sqlTbl, String groupName, JoinType joinType)
    {
        SQLTableGroup tableGrp = tableGroups.get(groupName);
        if (tableGrp == null)
        {
            tableGrp = new SQLTableGroup(groupName, joinType);
        }
        tableGrp.addTable(sqlTbl);
        tableGroups.put(groupName, tableGrp);
    }

    /**
     * Internal method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join (INNER, LEFT OUTER, RIGHT OUTER, CROSS, NON-ANSI)
     * @param sourceTable SQLTable to join from
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping Optional parent of this source mapping (when joining an impl of an interface)
     * @param targetTable SQLTable to join to
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping Optional parent of this target mapping (when joining an impl of an interface)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     */
    protected void addJoin(SQLJoin.JoinType joinType, 
            SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            SQLTable targetTable, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping,
            Object[] discrimValues)
    {
        if (tables == null)
        {
            throw new NucleusException("tables not set in statement!");
        }
        if (tables.containsValue(targetTable))
        {
            // Already have a join to this table
            // What if we have a cross join, and want to change to inner join?
            NucleusLogger.DATASTORE.debug("Attempt to join to " + targetTable + " but join already exists");
            return;
        }
        if (joinType == JoinType.RIGHT_OUTER_JOIN && !rdbmsMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.RIGHT_OUTER_JOIN))
        {
            throw new NucleusUserException("RIGHT OUTER JOIN is not supported by this datastore");
        }

        // Add the table to the referenced tables for this statement
        tables.put(targetTable.alias.getName(), targetTable);

        // Generate the join condition to use
        BooleanExpression joinCondition = getJoinConditionForJoin(sourceTable, sourceMapping, sourceParentMapping,
            targetTable, targetMapping, targetParentMapping, discrimValues);

        if (rdbmsMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.ANSI_JOIN_SYNTAX))
        {
            // "ANSI-92" style join
            SQLJoin join = new SQLJoin(joinType, targetTable, sourceTable, joinCondition);
            if (joins == null)
            {
                joins = new ArrayList<SQLJoin>();
            }

            int position = -1;
            if (queryGenerator != null && queryGenerator.processingOnClause())
            {
                // We are processing an ON condition, and this JOIN has been forced, so position it dependent on what it joins from
                if (primaryTable == sourceTable)
                {
                    if (joins.size() > 0)
                    {
                        position = 0;
                    }
                }
                else
                {
                    int i=1;
                    for (SQLJoin sqlJoin : joins)
                    {
                        if (sqlJoin.getJoinedTable() == sourceTable)
                        {
                            position = i;
                            break;
                        }
                        i++;
                    }
                }
            }

            if (position >= 0)
            {
                joins.add(position, join);
            }
            else
            {
                joins.add(join);
            }
        }
        else
        {
            // "ANSI-86" style join
            SQLJoin join = new SQLJoin(JoinType.NON_ANSI_JOIN, targetTable, sourceTable, null);
            if (joins == null)
            {
                joins = new ArrayList<SQLJoin>();
            }
            joins.add(join);

            // Specify joinCondition in the WHERE clause since not allowed in FROM clause with ANSI-86
            // TODO Cater for Oracle LEFT OUTER syntax "(+)"
            whereAnd(joinCondition, false);
        }
    }

    /**
     * Convenience method to generate the join condition between source and target tables for the supplied
     * mappings.
     * @param sourceTable Source table
     * @param sourceMapping Mapping in source table
     * @param sourceParentMapping Optional parent of this source mapping (if joining an impl of an interface)
     * @param targetTable Target table
     * @param targetMapping Mapping in target table
     * @param targetParentMapping Optional parent of this target mapping (if joining an impl of an interface)
     * @param discrimValues Optional discriminator values to further restrict
     * @return The join condition
     */
    protected BooleanExpression getJoinConditionForJoin(
            SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            SQLTable targetTable, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping,
            Object[] discrimValues)
    {
        BooleanExpression joinCondition = null;
        if (sourceMapping != null && targetMapping != null)
        {
            // Join condition(s) - INNER, LEFT OUTER, RIGHT OUTER joins
            if (sourceMapping.getNumberOfDatastoreMappings() != targetMapping.getNumberOfDatastoreMappings())
            {
                throw new NucleusException("Cannot join from " + sourceMapping + " to " + targetMapping +
                    " since they have different numbers of datastore columns!");
            }

            SQLExpressionFactory factory = rdbmsMgr.getSQLExpressionFactory();

            // Set joinCondition to be "source = target"
            SQLExpression sourceExpr = null;
            if (sourceParentMapping == null)
            {
                sourceExpr = factory.newExpression(this, sourceTable != null ? sourceTable : primaryTable, sourceMapping);
            }
            else
            {
                sourceExpr = factory.newExpression(this, sourceTable != null ? sourceTable : primaryTable, sourceMapping, sourceParentMapping);
            }

            SQLExpression targetExpr = null;
            if (targetParentMapping == null)
            {
                targetExpr = factory.newExpression(this, targetTable, targetMapping);
            }
            else
            {
                targetExpr = factory.newExpression(this, targetTable, targetMapping, targetParentMapping);
            }

            joinCondition = sourceExpr.eq(targetExpr);

            // Process discriminator for any additional conditions
            JavaTypeMapping discrimMapping = targetTable.getTable().getDiscriminatorMapping(false);
            if (discrimMapping != null && discrimValues != null)
            {
                SQLExpression discrimExpr = factory.newExpression(this, targetTable, discrimMapping);
                BooleanExpression discrimCondition = null;
                for (int i=0;i<discrimValues.length;i++)
                {
                    SQLExpression discrimVal = factory.newLiteral(this, discrimMapping, discrimValues[i]);
                    BooleanExpression condition = discrimExpr.eq(discrimVal);
                    if (discrimCondition == null)
                    {
                        discrimCondition = condition;
                    }
                    else
                    {
                        discrimCondition = discrimCondition.ior(condition);
                    }
                }
                if (discrimCondition != null)
                {
                    discrimCondition.encloseInParentheses();
                    joinCondition = joinCondition.and(discrimCondition);
                }
            }
        }
        return joinCondition;
    }

    /**
     * Method to return the namer for a particular schema.
     * If there is no instantiated namer for this schema then instantiates one.
     * @param namingSchema Table naming schema to use
     * @return The namer
     */
    protected synchronized SQLTableNamer getTableNamer(String namingSchema)
    {
        SQLTableNamer namer = tableNamerByName.get(namingSchema);
        if (namer == null)
        {
            // Instantiate the namer of this schema name (if available)
            try
            {
                namer = (SQLTableNamer)rdbmsMgr.getNucleusContext().getPluginManager().createExecutableExtension(
                    "org.datanucleus.store.rdbms.sql_tablenamer", "name", namingSchema, "class", null, null);
            }
            catch (Exception e)
            {
                throw new NucleusException("Attempt to find/instantiate SQL table namer " + namingSchema + " threw an exception", e);
            }
            tableNamerByName.put(namingSchema, namer);
        }
        return namer;
    }

    // --------------------------------- WHERE --------------------------------------

    /**
     * Method to add an AND condition to the WHERE clause.
     * @param expr The condition
     * @param applyToUnions whether to apply this and to any UNIONs in the statement
     */
    public void whereAnd(BooleanExpression expr, boolean applyToUnions)
    {
        invalidateStatement();

        if (expr instanceof BooleanLiteral && !expr.isParameter() && (Boolean)((BooleanLiteral)expr).getValue())
        {
            // Where condition is "TRUE" so omit
            return;
        }

        if (where == null)
        {
            where = expr;
        }
        else
        {
            where = where.and(expr);
        }

        if (unions != null && applyToUnions)
        {
            // Apply the where to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.whereAnd(expr, true);
            }
        }
    }

    /**
     * Method to add an OR condition to the WHERE clause.
     * @param expr The condition
     * @param applyToUnions Whether to apply to unions
     */
    public void whereOr(BooleanExpression expr, boolean applyToUnions)
    {
        invalidateStatement();

        if (where == null)
        {
            where = expr;
        }
        else
        {
            where = where.ior(expr);
        }

        if (unions != null && applyToUnions)
        {
            // Apply the where to all unions
            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                SQLStatement stmt = unionIter.next();
                stmt.whereOr(expr, true);
            }
        }
    }

    // --------------------------------- GROUPING --------------------------------------

    /**
     * Method to add a grouping expression to the query.
     * Adds the grouping to any unioned queries
     * @param expr The expression
     */
    public void addGroupingExpression(SQLExpression expr)
    {
        invalidateStatement();

        if (groupingExpressions == null)
        {
            groupingExpressions = new ArrayList();
        }
        groupingExpressions.add(expr);
        aggregated = true;

        if (unions != null)
        {
            // Apply the grouping to all unions
            Iterator<SQLStatement> i = unions.iterator();
            while (i.hasNext())
            {
                i.next().addGroupingExpression(expr);
            }
        }
    }

    // --------------------------------- HAVING --------------------------------------

    /**
     * Mutator for the "having" expression.
     * @param expr Boolean expression for the having clause
     */
    public void setHaving(BooleanExpression expr)
    {
        invalidateStatement();

        having = expr;
        aggregated = true;

        if (unions != null)
        {
            // Apply the having to all unions
            Iterator<SQLStatement> i = unions.iterator();
            while (i.hasNext())
            {
                i.next().setHaving(expr);
            }
        }
    }

    // --------------------------------- ORDERING --------------------------------------

    /**
     * Mutator for the ordering criteria.
     * @param exprs The expressions to order by
     * @param descending Whether each expression is ascending/descending
     */
    public void setOrdering(SQLExpression[] exprs, boolean[] descending)
    {
        setOrdering(exprs, descending, null);
    }

    /**
     * Mutator for the ordering criteria.
     * @param exprs The expressions to order by
     * @param descending Whether each expression is ascending/descending
     * @param nullOrders Ordering for nulls (if provided)
     */
    public void setOrdering(SQLExpression[] exprs, boolean[] descending, NullOrderingType[] nullOrders)
    {
        if (exprs != null && descending != null && exprs.length != descending.length)
        {
            throw new NucleusException(Localiser.msg("052503", "" + exprs.length, "" + descending.length)).setFatal();
        }

        invalidateStatement();

        orderingExpressions = exprs;
        orderingDirections = descending;
        orderNullDirectives = nullOrders;
    }

    // --------------------------------- RANGE --------------------------------------

    /**
     * Method to add a range constraint on any SELECT.
     * This typically will use LIMIT/OFFSET where they are supported by the underlying RDBMS.
     * @param offset The offset to start from
     * @param count The number of records to return
     */
    public void setRange(long offset, long count)
    {
        invalidateStatement();

        this.rangeOffset = offset;
        this.rangeCount = count;
    }

    // --------------------------------- STATEMENT ----------------------------------

    /**
     * Accessor for the SQL SELECT statement.
     * If any mutator method has been called since this was last called the SQL will be regenerated
     * otherwise the SQL is cached.
     * @return The SQL statement
     */
    public synchronized SQLText getSelectStatement()
    {
        if (sql != null)
        {
            return sql;
        }

        DatastoreAdapter dba = getDatastoreAdapter();
        boolean lock = false;
        Boolean val = (Boolean)getValueForExtension("lock-for-update");
        if (val != null)
        {
            lock = val.booleanValue();
        }

        boolean addAliasToAllSelects = false;
        if (rangeOffset > 0 || rangeCount > -1)
        {
            if (dba.getRangeByRowNumberColumn2().length() > 0)
            {
                // Doing "SELECT * FROM (...)" so to be safe we need alias on all selects
                addAliasToAllSelects = true;
            }
        }

        // SELECT ..., ..., ...
        sql = new SQLText("SELECT ");

        if (distinct)
        {
            sql.append("DISTINCT ");
        }

        addOrderingColumnsToSelect();

        if (selectedItems.isEmpty())
        {
            // Nothing selected so select all
            sql.append("*");
        }
        else
        {
            int autoAliasNum = 0;
            Iterator<SelectedItem> selectItemIter = selectedItems.iterator();
            while (selectItemIter.hasNext())
            {
                SelectedItem selectedItem = selectItemIter.next();
                SQLText selectedST = selectedItem.getSQLText();
                sql.append(selectedST);

                if (selectedItem.getAlias() != null)
                {
                    sql.append(" AS ").append(rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(selectedItem.getAlias()));
                }
                else
                {
                    if (addAliasToAllSelects)
                    {
                        // This query needs an alias on all selects, so add "DN_{X}"
                        sql.append(" AS ").append(rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase("DN_" + autoAliasNum));
                        autoAliasNum++;
                    }
                }

                if (selectItemIter.hasNext())
                {
                    sql.append(',');
                }
            }
            if ((rangeOffset > -1 || rangeCount > -1) && dba.getRangeByRowNumberColumn().length() > 0)
            {
                // Add a ROW NUMBER column if supported as the means of handling ranges by the RDBMS
                sql.append(',').append(dba.getRangeByRowNumberColumn()).append(" rn");
            }
        }

        // FROM ...
        sql.append(" FROM ");
        sql.append(primaryTable.toString());
        if (lock && dba.supportsOption(DatastoreAdapter.LOCK_OPTION_PLACED_AFTER_FROM))
        {
            sql.append(" WITH ").append(dba.getSelectWithLockOption());
        }
        if (joins != null)
        {
            sql.append(getSqlForJoins(lock));
        }

        // WHERE ...
        if (where != null)
        {
            sql.append(" WHERE ").append(where.toSQLText());
        }

        // GROUP BY ...
        if (groupingExpressions != null)
        {
            List groupBy = new ArrayList();
            Iterator<SQLExpression> groupIter = groupingExpressions.iterator();
            while (groupIter.hasNext())
            {
                SQLExpression expr = groupIter.next();
                String exprText = expr.toSQLText().toSQL();
                if (!groupBy.contains(exprText))
                {
                    groupBy.add(exprText);
                }
            }

            if (dba.supportsOption(DatastoreAdapter.GROUP_BY_REQUIRES_ALL_SELECT_PRIMARIES))
            {
                // Check that all select items are represented in the grouping for those RDBMS that need that
                for (SelectedItem selItem : selectedItems)
                {
                    if (selItem.isPrimary())
                    {
                        String selSQL = selItem.getSQLText().toSQL();
                        boolean selExists = false;
                        for (SQLExpression grpExpr : groupingExpressions)
                        {
                            String grpExprSQL = grpExpr.toSQLText().toSQL();
                            if (grpExprSQL.equals(selSQL))
                            {
                                selExists = true;
                                break;
                            }
                        }
                        if (!selExists)
                        {
                            groupBy.add(selSQL);
                        }
                    }
                }
            }

            if (groupBy.size() > 0 && aggregated)
            {
                sql.append(" GROUP BY ");
                for (int i=0; i<groupBy.size(); i++)
                {
                    if (i > 0)
                    {
                        sql.append(',');
                    }
                    sql.append((String)groupBy.get(i));
                }
            }
        }

        // HAVING ...
        if (having != null)
        {
            sql.append(" HAVING ").append(having.toSQLText());
        }

        if (unions != null)
        {
            // Add on any UNIONed statements
            if (!dba.supportsOption(DatastoreAdapter.UNION_SYNTAX))
            {
                throw new NucleusException(Localiser.msg("052504", "UNION")).setFatal();
            }

            Iterator<SQLStatement> unionIter = unions.iterator();
            while (unionIter.hasNext())
            {
                if (dba.supportsOption(DatastoreAdapter.USE_UNION_ALL))
                {
                    sql.append(" UNION ALL ");
                }
                else
                {
                    sql.append(" UNION ");
                }

                SQLStatement stmt = unionIter.next();
                SQLText unionSql = stmt.getSelectStatement();
                sql.append(unionSql);
            }
        }

        // ORDER BY ...
        SQLText orderStmt = generateOrderingStatement();
        if (orderStmt != null)
        {
            sql.append(" ORDER BY ").append(orderStmt);
        }

        // RANGE
        if (rangeOffset > -1 || rangeCount > -1)
        {
            // Add a LIMIT clause to end of statement if supported by the adapter
            String limitClause;
            // For MSSQL Server, we need order by clause for the correct range syntax
            if (dba.getClass().getName().equalsIgnoreCase("org.datanucleus.store.rdbms.adapter.MSSQLServerAdapter")) 
            {
              boolean hasOrderBy = (orderStmt != null);
              limitClause = dba.getRangeByLimitEndOfStatementClause(rangeOffset, rangeCount, hasOrderBy);
            }
            else 
            {
              limitClause = dba.getRangeByLimitEndOfStatementClause(rangeOffset, rangeCount);
            }
            if (limitClause.length() > 0)
            {
                sql.append(" ").append(limitClause);
            }
        }

        if (lock && dba.supportsOption(DatastoreAdapter.LOCK_WITH_SELECT_FOR_UPDATE))
        {
            // Add any required locking based on the RDBMS capability
            if (distinct && !dba.supportsOption(DatastoreAdapter.DISTINCT_WITH_SELECT_FOR_UPDATE))
            {
                NucleusLogger.QUERY.warn(Localiser.msg("052502"));
            }
            else if (groupingExpressions != null && !dba.supportsOption(DatastoreAdapter.GROUPING_WITH_SELECT_FOR_UPDATE))
            {
                NucleusLogger.QUERY.warn(Localiser.msg("052506"));
            }
            else if (having != null && !dba.supportsOption(DatastoreAdapter.HAVING_WITH_SELECT_FOR_UPDATE))
            {
                NucleusLogger.QUERY.warn(Localiser.msg("052507"));
            }
            else if (orderingExpressions != null && !dba.supportsOption(DatastoreAdapter.ORDERING_WITH_SELECT_FOR_UPDATE))
            {
                NucleusLogger.QUERY.warn(Localiser.msg("052508"));
            }
            else if (joins != null && !joins.isEmpty() && !dba.supportsOption(DatastoreAdapter.MULTITABLES_WITH_SELECT_FOR_UPDATE))
            {
                NucleusLogger.QUERY.warn(Localiser.msg("052509"));
            }
            else
            {
                sql.append(" " + dba.getSelectForUpdateText());
                if (dba.supportsOption(DatastoreAdapter.SELECT_FOR_UPDATE_NOWAIT))
                {
                    Boolean nowait = (Boolean) getValueForExtension("for-update-nowait");
                    if (nowait != null)
                    {
                        sql.append(" NOWAIT");
                    }
                }
            }
        }
        if (lock && !dba.supportsOption(DatastoreAdapter.LOCK_WITH_SELECT_FOR_UPDATE) &&
            !dba.supportsOption(DatastoreAdapter.LOCK_OPTION_PLACED_AFTER_FROM) &&
            !dba.supportsOption(DatastoreAdapter.LOCK_OPTION_PLACED_WITHIN_JOIN))
        {
            NucleusLogger.QUERY.warn("Requested locking of query statement, but this RDBMS doesn't support a convenient mechanism");
        }

        if (rangeOffset > 0 || rangeCount > -1)
        {
            if (dba.getRangeByRowNumberColumn2().length() > 0)
            {
                // Oracle-specific using ROWNUM. Creates a query of the form
                // SELECT * FROM (
                //     SELECT subq.*, ROWNUM rn FROM (
                //         SELECT x1, x2, ... FROM ... WHERE ... ORDER BY ...
                //     ) subq
                // ) WHERE rn > {offset} AND rn <= {count}
                SQLText userSql = sql;

                // SELECT all columns of userSql, plus ROWNUM, with the FROM being the users query
                SQLText innerSql = new SQLText("SELECT subq.*");
                innerSql.append(',').append(dba.getRangeByRowNumberColumn2()).append(" rn");
                innerSql.append(" FROM (").append(userSql).append(") subq ");

                // Put that query as the FROM of the outer query, and apply the ROWNUM restrictions
                SQLText outerSql = new SQLText("SELECT * FROM (").append(innerSql).append(") ");
                outerSql.append("WHERE ");
                if (rangeOffset > 0)
                {
                    outerSql.append("rn > " + rangeOffset);
                    if (rangeCount > -1)
                    {
                        outerSql.append(" AND rn <= " + (rangeCount+rangeOffset));
                    }
                }
                else
                {
                    outerSql.append(" rn <= " + rangeCount);
                }

                sql = outerSql;
            }
            else if (dba.getRangeByRowNumberColumn().length() > 0)
            {
                // DB2-specific ROW_NUMBER weirdness. Creates a query of the form
                // SELECT subq.x1, subq.x2, ... FROM (
                //     SELECT x1, x2, ..., {keyword} rn FROM ... WHERE ... ORDER BY ...) subq
                // WHERE subq.rn >= {offset} AND subq.rn < {count}
                // This apparently works for DB2 (unverified, but claimed by IBM employee)
                SQLText userSql = sql;
                sql = new SQLText("SELECT ");
                Iterator<SelectedItem> selectedItemIter = selectedItems.iterator();
                while (selectedItemIter.hasNext())
                {
                    SelectedItem selectedItemExpr = selectedItemIter.next();
                    sql.append("subq.");
                    String selectedCol = selectedItemExpr.getSQLText().toSQL();
                    if (selectedItemExpr.getAlias() != null)
                    {
                        selectedCol = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(selectedItemExpr.getAlias());
                    }
                    else
                    {
                        // strip out qualifier when encountered from column name since we are adding a new qualifier above.
                        // NOTE THAT THIS WILL FAIL IF THE ORIGINAL QUERY HAD "A0.COL1, B0.COL1" IN THE SELECT
                        int dotIndex = selectedCol.indexOf(".");
                        if (dotIndex > 0) 
                        {
                            // Remove qualifier name and the dot
                            selectedCol = selectedCol.substring(dotIndex+1);
                        }
                    }

                    sql.append(selectedCol);
                    if (selectedItemIter.hasNext())
                    {
                        sql.append(',');
                    }
                }
                sql.append(" FROM (").append(userSql).append(") subq WHERE ");
                if (rangeOffset > 0)
                {
                    sql.append("subq.rn").append(">").append("" + rangeOffset);
                }
                if (rangeCount > 0)
                {
                    if (rangeOffset > 0)
                    {
                        sql.append(" AND ");
                    }
                    sql.append("subq.rn").append("<=").append("" + (rangeCount + rangeOffset));
                }
            }
        }

        return sql;
    }

    /**
     * Convenience method to reorder the joins to be in logical order.
     * If a join needed to be changed during the generation process, it will have been removed and then
     * the replacement added later. This method reorders the joins so that the joins are only relative to
     * "known" tables.
     */
    private void reorderJoins(List knownJoins, List joinsToAdd)
    {
        if (joinsToAdd == null)
        {
            requiresJoinReorder = false;
            return;
        }

        while (joinsToAdd.size() > 0)
        {
            Iterator<SQLJoin> joinIter = joinsToAdd.iterator();
            int origSize = joinsToAdd.size();
            while (joinIter.hasNext())
            {
                SQLJoin join = joinIter.next();
                if (join.getType() == JoinType.CROSS_JOIN)
                {
                    // Cross joins don't relate to any other table so are fine
                    knownJoins.add(join);
                    joinIter.remove();
                }
                else if (join.getType() == JoinType.NON_ANSI_JOIN)
                {
                    // Non-ANSI joins use the WHERE clause so are fine
                    knownJoins.add(join);
                    joinIter.remove();
                }
                else if (join.getJoinedTable().equals(primaryTable))
                {
                    // Joins to the primary table are fine
                    knownJoins.add(join);
                    joinIter.remove();
                }
                else
                {
                    Iterator<SQLJoin> knownJoinIter = knownJoins.iterator();
                    boolean valid = false;
                    while (knownJoinIter.hasNext())
                    {
                        SQLJoin currentJoin = knownJoinIter.next();
                        if (join.getJoinedTable().equals(currentJoin.getTable()))
                        {
                            valid = true;
                            break;
                        }
                    }
                    if (valid)
                    {
                        // Only used known joins so fine
                        knownJoins.add(join);
                        joinIter.remove();
                    }
                }
            }

            if (joinsToAdd.size() == origSize)
            {
                // Somehow the user has ended up with a circular pattern of joins
                throw new NucleusException("Unable to reorder joins for SQL statement since circular!" +
                    " Consider reordering the components in the WHERE clause : affected joins - " + StringUtils.collectionToString(joinsToAdd));
            }
        }
        requiresJoinReorder = false;
    }

    /**
     * Convenience method to return the JOIN clause implied by the "joins" List.
     * @param lock Whether to add locking on the join clause (only for some RDBMS)
     * @return The SQL for the join clause
     */
    private SQLText getSqlForJoins(boolean lock)
    {
        SQLText sql = new SQLText();
        DatastoreAdapter dba = getDatastoreAdapter();
        if (requiresJoinReorder)
        {
            List<SQLJoin> theJoins = new ArrayList<SQLJoin>(joins.size());
            reorderJoins(theJoins, joins);
            joins = theJoins;
        }
        Iterator<SQLJoin> iter = joins.iterator();
        while (iter.hasNext())
        {
            SQLJoin join = iter.next();
            if (join.getType() == JoinType.CROSS_JOIN)
            {
                if (dba.supportsOption(DatastoreAdapter.ANSI_CROSSJOIN_SYNTAX))
                {
                    // ANSI-92 style joins, separate joins by space
                    sql.append(" ").append(join.toSQLText(dba, lock));
                }
                else if (dba.supportsOption(DatastoreAdapter.CROSSJOIN_ASINNER11_SYNTAX))
                {
                    sql.append(" INNER JOIN " + join.getTable() + " ON 1=1");
                }
                else
                {
                    // "ANSI-86" style cross join, separate join by comma
                    sql.append(",").append(join.getTable().toString());
                }
            }
            else
            {
                if (dba.supportsOption(DatastoreAdapter.ANSI_JOIN_SYNTAX))
                {
                    // ANSI-92 style joins, separate joins by space
                    sql.append(" ").append(join.toSQLText(dba, lock));
                }
                else
                {
                    // "ANSI-86" style joins, separate joins by comma
                    sql.append(",").append(join.toSQLText(dba, lock));
                }
            }
        }
        return sql;
    }

    /**
     * Accessor for the SQL UPDATE statement.
     * If any mutator method has been called since this was last called the SQL will be regenerated
     * otherwise the SQL is cached.
     * @return The SQL statement for UPDATE
     */
    public synchronized SQLText getUpdateStatement()
    {
        if (sql != null)
        {
            return sql;
        }

        // Generate the SET component of the statement since some need it to formulate the basic UPDATE component
        SQLText setSQL = new SQLText("SET ");
        if (updates != null && updates.length > 0)
        {
            for (int i=0;i<updates.length;i++)
            {
                if (updates[i] != null)
                {
                    if (i != 0)
                    {
                        setSQL.append(",");
                    }
                    setSQL.append(updates[i].toSQLText());
                }
            }
        }

        sql = rdbmsMgr.getDatastoreAdapter().getUpdateTableStatement(primaryTable, setSQL);

        if (joins != null)
        {
            // Joins present so convert to "... WHERE EXISTS (SELECT * FROM OTHER_TBL ...)"
            Iterator<SQLJoin> joinIter = joins.iterator();

            // Create sub-statement selecting the first joined table, joining back to the outer statement
            SQLJoin subJoin = joinIter.next();
            SQLStatement subStmt = new SQLStatement(this, rdbmsMgr, subJoin.getTable().getTable(), subJoin.getTable().getAlias(), subJoin.getTable().getGroupName());
            subStmt.whereAnd(subJoin.getCondition(), false);
            if (where != null)
            {
                // Move the WHERE clause to the sub-statement
                subStmt.whereAnd(where, false);
            }

            // Put any remaining joins into the sub-statement
            while (joinIter.hasNext())
            {
                SQLJoin join = joinIter.next();
                subStmt.joins.add(join);
            }

            // Set WHERE clause of outer statement to "EXISTS (sub-statement)"
            BooleanExpression existsExpr = new BooleanSubqueryExpression(this, "EXISTS", subStmt);
            where = existsExpr;
        }
        if (where != null)
        {
            sql.append(" WHERE ").append(where.toSQLText());
        }

        return sql;
    }

    /**
     * Accessor for the SQL DELETE statement. Generates a statement like
     * <code>DELETE FROM tbl1 A0 WHERE A0.xxx = yyy</code>
     * If any mutator method has been called since this was last called the SQL will be regenerated
     * otherwise the SQL is cached.
     * @return The SQL statement for DELETE
     */
    public synchronized SQLText getDeleteStatement()
    {
        if (sql != null)
        {
            return sql;
        }

        sql = new SQLText(rdbmsMgr.getDatastoreAdapter().getDeleteTableStatement(primaryTable));

        if (joins != null)
        {
            // Joins present so convert to "DELETE FROM MYTABLE WHERE EXISTS (SELECT * FROM OTHER_TBL ...)"
            Iterator<SQLJoin> joinIter = joins.iterator();

            // Create sub-statement selecting the first joined table, joining back to the outer statement
            SQLJoin subJoin = joinIter.next();
            SQLStatement subStmt = new SQLStatement(this, rdbmsMgr, subJoin.getTable().getTable(), subJoin.getTable().getAlias(), subJoin.getTable().getGroupName());
            subStmt.whereAnd(subJoin.getCondition(), false);
            if (where != null)
            {
                // Move the WHERE clause to the sub-statement
                subStmt.whereAnd(where, false);
            }

            // Put any remaining joins into the sub-statement
            while (joinIter.hasNext())
            {
                SQLJoin join = joinIter.next();
                subStmt.joins.add(join);
            }

            // Set WHERE clause of outer statement to "EXISTS (sub-statement)"
            BooleanExpression existsExpr = new BooleanSubqueryExpression(this, "EXISTS", subStmt);
            where = existsExpr;
        }
        if (where != null)
        {
            sql.append(" WHERE ").append(where.toSQLText());
        }

        return sql;
    }

    /** Positions of order columns in the SELECT (for datastores that require ordering using those). */
    private int[] orderingColumnIndexes;

    /**
     * Convenience method to generate the ordering statement to add to the overall query statement.
     * @return The ordering statement
     */
    protected SQLText generateOrderingStatement()
    {
        SQLText orderStmt = null;
        if (orderingExpressions != null && orderingExpressions.length > 0)
        {
            DatastoreAdapter dba = getDatastoreAdapter();
            if (dba.supportsOption(DatastoreAdapter.ORDERBY_USING_SELECT_COLUMN_INDEX))
            {
                // Order using the indexes of the ordering columns in the SELECT
                orderStmt = new SQLText();
                for (int i=0; i<orderingExpressions.length; ++i)
                {
                    if (i > 0)
                    {
                        orderStmt.append(',');
                    }
                    orderStmt.append(Integer.toString(orderingColumnIndexes[i]));
                    if (orderingDirections[i])
                    {
                        orderStmt.append(" DESC");
                    }
                    if (orderNullDirectives != null && orderNullDirectives[i] != null && dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_DIRECTIVES))
                    {
                        // Apply "NULLS [FIRST | LAST]" since supported by this datastore
                        orderStmt.append(" " + (orderNullDirectives[i] == NullOrderingType.NULLS_FIRST ? "NULLS FIRST" : "NULLS LAST"));
                    }
                }
            }
            else
            {
                // TODO Cater for ResultAliasExpression, so we just put the order aliasName
                // Order using column aliases "NUCORDER{i}"
                orderStmt = new SQLText();
                boolean needsSelect = dba.supportsOption(DatastoreAdapter.INCLUDE_ORDERBY_COLS_IN_SELECT);
                if (parent != null)
                {
                    // Don't select ordering columns with subqueries, since we will select just the required column(s)
                    needsSelect = false;
                }
                for (int i=0; i<orderingExpressions.length; ++i)
                {
                    SQLExpression orderExpr = orderingExpressions[i];
                    boolean orderDirection = orderingDirections[i];
                    NullOrderingType orderNullDirective = (orderNullDirectives != null ? orderNullDirectives[i] : null);

                    if (i > 0)
                    {
                        orderStmt.append(',');
                    }

                    if (needsSelect && !aggregated)
                    {
                        if (orderExpr instanceof ResultAliasExpression)
                        {
                            String orderStr = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(((ResultAliasExpression)orderExpr).getResultAlias());
                            addOrderComponent(orderStmt, orderStr, orderExpr, orderDirection, orderNullDirective, dba);
                        }
                        else
                        {
                            // Order by the "NUCORDER?" if we need them to be selected and it isn't an aggregate
                            String orderString = "NUCORDER" + i;
                            if (orderExpr.getNumberOfSubExpressions() == 1)
                            {
                                String orderStr = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(orderString);
                                addOrderComponent(orderStmt, orderStr, orderExpr, orderDirection, orderNullDirective, dba);
                            }
                            else
                            {
                                DatastoreMapping[] mappings = orderExpr.getJavaTypeMapping().getDatastoreMappings();
                                for (int j=0;j<mappings.length;j++)
                                {
                                    String orderStr = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(orderString + "_" + j);
                                    addOrderComponent(orderStmt, orderStr, orderExpr, orderDirection, orderNullDirective, dba);

                                    if (j < mappings.length-1)
                                    {
                                        orderStmt.append(',');
                                    }
                                }
                            }
                        }
                    }
                    else
                    {
                        if (orderExpr instanceof ResultAliasExpression)
                        {
                            String orderStr = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase(((ResultAliasExpression)orderExpr).getResultAlias());
                            addOrderComponent(orderStmt, orderStr, orderExpr, orderDirection, orderNullDirective, dba);
                        }
                        else
                        {
                            // Order by the "THIS.COLUMN" otherwise
                            addOrderComponent(orderStmt, orderExpr.toSQLText().toSQL(), orderExpr, orderDirection, orderNullDirective, dba);
                        }
                    }
                }
            }
        }
        return orderStmt;
    }

    protected void addOrderComponent(SQLText orderST, String orderString, SQLExpression orderExpr, boolean orderDirection, NullOrderingType orderNullDirective, DatastoreAdapter dba)
    {
        if (orderNullDirective != null && !dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_USING_ISNULL) && !dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_DIRECTIVES) &&
            !dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_USING_COLUMN_IS_NULL))
        {
            NucleusLogger.DATASTORE_RETRIEVE.warn("Query contains NULLS directive yet this datastore doesn't provide any support for handling this. Nulls directive will be ignored");
        }

        String orderParam = dba.getOrderString(rdbmsMgr, orderString, orderExpr);
        if (orderNullDirective == NullOrderingType.NULLS_LAST)
        {
            if (dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_USING_ISNULL) && orderExpr.getSQLTable() != null)
            {
                // Datastore requires nulls last using ISNULL extra ordering clause. Note : don't do this when the ordering component is not a simple column
                orderST.append("ISNULL(").append(orderParam).append("),");
            }
            else if (dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_USING_COLUMN_IS_NULL) && orderExpr.getSQLTable() != null)
            {
                // Datastore requires nulls last using "{col} IS NULL" extra ordering clause. Note : don't do this when the ordering component is not a simple column
                orderST.append(orderParam).append(" IS NULL,");
            }
        }

        orderST.append(orderParam);
        orderST.append(orderDirection ? " DESC" : "");

        if (orderNullDirective != null && dba.supportsOption(DatastoreAdapter.ORDERBY_NULLS_DIRECTIVES))
        {
            // Apply "NULLS [FIRST | LAST]" directly since supported by this datastore
            orderST.append(" " + (orderNullDirective == NullOrderingType.NULLS_FIRST ? "NULLS FIRST" : "NULLS LAST"));
        }
    }

    /**
     * Convenience method to add any necessary columns to the SELECT that are needed
     * by the ordering constraint.
     */
    protected void addOrderingColumnsToSelect()
    {
        // TODO Cater for these columns already being selected but with no alias, so add the alias to the already selected column
        if (orderingExpressions != null && parent == null) // Don't do this for subqueries, since we will be selecting just the necessary column(s)
        {
            // Add any ordering columns to the SELECT
            DatastoreAdapter dba = getDatastoreAdapter();
            if (dba.supportsOption(DatastoreAdapter.ORDERBY_USING_SELECT_COLUMN_INDEX))
            {
                // Order using the indexes of the ordering columns in the SELECT
                orderingColumnIndexes = new int[orderingExpressions.length];

                // Add the ordering columns to the selected list, saving the positions
                for (int i=0; i<orderingExpressions.length; ++i)
                {
                    orderingColumnIndexes[i] = selectItem(orderingExpressions[i].toSQLText(), null, !aggregated);
                    if (unions != null)
                    {
                        Iterator<SQLStatement> iterator = unions.iterator();
                        while (iterator.hasNext())
                        {
                            SQLStatement stmt = iterator.next();
                            stmt.selectItem(orderingExpressions[i].toSQLText(), null, !aggregated);
                        }
                    }
                }
            }
            else if (dba.supportsOption(DatastoreAdapter.INCLUDE_ORDERBY_COLS_IN_SELECT))
            {
                // Order using column aliases "NUCORDER{i}"
                for (int i=0; i<orderingExpressions.length; ++i)
                {
                    if (orderingExpressions[i] instanceof ResultAliasExpression)
                    {
                        // Nothing to do since this is ordering by a result alias
                    }
                    else if (orderingExpressions[i].getNumberOfSubExpressions() == 1 || aggregated)
                    {
                        String alias = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase("NUCORDER" + i);
                        if (unions != null)
                        {
                            Iterator<SQLStatement> iterator = unions.iterator();
                            while (iterator.hasNext())
                            {
                                SQLStatement stmt = iterator.next();
                                stmt.selectItem(orderingExpressions[i].toSQLText(), aggregated ? null : alias, !aggregated);
                            }
                        }

                        selectItem(orderingExpressions[i].toSQLText(), aggregated ? null : alias, !aggregated);
                    }
                    else
                    {
                        JavaTypeMapping m = orderingExpressions[i].getJavaTypeMapping();

                        DatastoreMapping[] mappings = m.getDatastoreMappings();
                        for (int j=0;j<mappings.length;j++)
                        {
                            String alias = rdbmsMgr.getIdentifierFactory().getIdentifierInAdapterCase("NUCORDER" + i + "_" + j);
                            DatastoreIdentifier aliasId = rdbmsMgr.getIdentifierFactory().newColumnIdentifier(alias);
                            SQLColumn col = new SQLColumn(orderingExpressions[i].getSQLTable(), mappings[j].getColumn(), aliasId);
                            selectItem(new SQLText(col.getColumnSelectString()), alias, !aggregated);

                            if (unions != null)
                            {
                                Iterator<SQLStatement> iterator = unions.iterator();
                                while (iterator.hasNext())
                                {
                                    SQLStatement stmt = iterator.next();
                                    stmt.selectItem(new SQLText(col.getColumnSelectString()), alias, !aggregated);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Method to uncache the generated SQL (because some condition has changed).
     */
    protected void invalidateStatement()
    {
        sql = null;
    }

    /**
     * Method to dump the statement to the supplied log (debug level).
     * Logs the (SELECT or UPDATE) SQL that this statement equates to, and the TableGroup(s) and their associated tables.
     * @param logger The logger
     */
    public void log(NucleusLogger logger)
    {
        // Log the statement (assumed to be SELECT)
        if (updates != null)
        {
            logger.debug("SQLStatement : " + getUpdateStatement().toSQL());
        }
        else
        {
            logger.debug("SQLStatement : " + getSelectStatement().toSQL());
        }

        // Log the table groups
        Iterator grpIter = tableGroups.keySet().iterator();
        while (grpIter.hasNext())
        {
            String grpName = (String)grpIter.next();
            logger.debug("SQLStatement : TableGroup=" + tableGroups.get(grpName));
        }
    }
}