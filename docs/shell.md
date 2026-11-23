# The shell

```
./monoql                       interactive
./monoql -e "show tables"      run a statement (or a !command) and exit
./monoql -f script.sql         run a script and exit
./monoql < script.sql          likewise
./monoql --help
```

`./monoql` builds the shell if it is out of date (set `MONOQL_SKIP_BUILD=1`
to skip that) and then starts it. The shell holds a `Session` and calls it
directly. It does not use JDBC.

## Options

| Option | |
|---|---|
| `-e`, `--execute SQL` | Run SQL or a `!command`, then exit. Repeatable; they share one session. |
| `-f`, `--file FILE` | Run a script, then exit. Repeatable. |
| `-i`, `--init FILE` | Run a script before starting. Default: `~/.monoql/init.sql`, if it exists. |
| `-j`, `--jar PATH` | Add a jar to the class path. Repeatable. This is how a JDBC driver for a `jdbc` catalog, or a jar containing a catalog provider, is made available. |
| `--format FORMAT` | `table` (default), `vertical`, `csv` or `json`. |
| `--no-color`, `--no-timing` | |

With `-e`, `-f` or piped input, the exit status is 1 if anything failed, and
a script stops at its first failure.

## Editing

- SQL ends with a semicolon, and continues across lines until it gets one.
  Several statements can go on one line.
- A line starting with `!` is a command, and needs no semicolon.
- **Highlighting** as you type: keywords, strings, numbers, quoted
  identifiers, comments. An unterminated string or comment shows in red.
- **Tab** completes commands, keywords, and the names of catalogs, schemas,
  tables and columns. Names are looked up in the live schema tree, so
  completion sees whatever the planner would, in any catalog; and it follows
  paths (`metadata.gr⇥`, `orders.⇥`). Names are offered in the case you are
  typing in, and quoted when they need to be.
- Ctrl-R searches history, which is kept in `~/.monoql/history`. Ctrl-C
  abandons the current input, or stops the statement that is running.
  Ctrl-D leaves.
- The prompt shows the current catalog and schema.

## Commands

| Command | |
|---|---|
| `!help` | List commands. |
| `!quit` | Leave. Also `!exit`, `!q`. |
| `!catalogs`, `!schemas`, `!tables`, `!views`, `!functions`, `!triggers`, `!roles`, `!users`, `!grants`, `!settings` | Shorthand for the `SHOW` statement of the same name; anything after the command is appended, e.g. `!tables in sales`. |
| `!describe table` | `DESCRIBE table`. |
| `!use [catalog.]schema` | `USE`. |
| `!metadata` | List the tables of the `METADATA` schema, with their columns. |
| `!lower statement` | Print the DML that a statement is shorthand for, without running it. |
| `!explain statement` | Print the plan. For a DDL statement, the plan of what it lowers to. |
| `!run file` | Run a script: statements, and commands on lines of their own. |
| `!format [table\|vertical\|csv\|json]` | Show or set the output format. Every format prints rows as they are read. `table` prints 1,000 rows at a time, each batch as a table of its own with its own header and column widths; `vertical` shows long values whole. |
| `!timing [on\|off]` | Show or set timing. |
| `!verbose [on\|off]` | Show or set printing of stack traces with errors. |
| `!history`, `!clear` | |
