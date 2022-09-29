package io.mats3.test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.PooledConnection;
import javax.sql.XAConnection;
import javax.sql.XADataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapped H2 DataBase DataSource which has a couple of extra methods which simplifies testing, in particular the
 * {@link #cleanDatabase()}, and {@link #createDataTable()} method with associated convenience methods for storing and
 * getting simple values.
 */
public class TestH2DataSource implements XADataSource, DataSource, ConnectionPoolDataSource {
    private static final Logger log = LoggerFactory.getLogger(TestH2DataSource.class);

    /**
     * System property ("-D" jvm argument) that if set will change the method {@link #createStandard()} from returning
     * an in-memory H2 DataSource, to instead return a DataSource using the URL from the value, with the special case
     * that if the value is "{@link #SYSPROP_VALUE_FILE_BASED file}", it will be
     * <code>"jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE"</code>.
     * <p>
     * Value is {@code "mats.test.activemq"}
     */
    public static final String SYSPROP_MATS_TEST_H2 = "mats.test.h2";

    /**
     * If the value of {@link #SYSPROP_MATS_TEST_H2} is this value, the {@link #createStandard()} will use the URL
     * {@link #FILE_BASED_TEST_H2_DATABASE_URL}, which is <code>"jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE"</code>.
     * <p>
     * Value is {@code "file"}
     */
    public static final String SYSPROP_VALUE_FILE_BASED = "file";

    public static final String FILE_BASED_TEST_H2_DATABASE_URL = "jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE";
    public static final String IN_MEMORY_TEST_H2_DATABASE_URL = "jdbc:h2:mem:matsTestH2DB_uniqueRnd_$random$;DB_CLOSE_DELAY=-1";

    /**
     * Creates an in-memory {@link TestH2DataSource} as specified by the URL {@link #IN_MEMORY_TEST_H2_DATABASE_URL},
     * which is <code>"jdbc:h2:mem:matsTestH2DB_[randomness];DB_CLOSE_DELAY=-1"</code>, <b>unless</b> the System
     * Property {@link #SYSPROP_MATS_TEST_H2} (<code>"mats.test.h2"</code>) is directly set to a different URL to use,
     * with the special case that if it is {@link #SYSPROP_VALUE_FILE_BASED} (<code>"file"</code>), in which case
     * {@link #FILE_BASED_TEST_H2_DATABASE_URL} (<code>"jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE</code>) is used as URL.
     * <p />
     * <b>Notice that the method {@link #cleanDatabase()} is invoked when creating the DataSource, which is relevant
     * when using the file-based variant.</b>
     *
     * @return the created {@link TestH2DataSource}.
     */
    public static TestH2DataSource createStandard() {
        String sysprop_matsTestH2 = System.getProperty(SYSPROP_MATS_TEST_H2);
        // ?: Was it set?
        if (sysprop_matsTestH2 == null) {
            // -> No, not set - so return normal in-memory database
            return createInMemoryRandom();
        }
        // E-> The System Property was set

        // ?: Was it the special "file" value?
        if (sysprop_matsTestH2.equalsIgnoreCase(SYSPROP_VALUE_FILE_BASED)) {
            // -> Yes, special "file" value
            return createFileBased();
        }

        // E-> No, not special value, so treat it as a URL directly
        return create(sysprop_matsTestH2);
    }

    /**
     * Creates a {@link TestH2DataSource} using the URL {@link #FILE_BASED_TEST_H2_DATABASE_URL}, which is
     * <code>"jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE"</code>.
     *
     * @return the created {@link TestH2DataSource}.
     */
    public static TestH2DataSource createFileBased() {
        return create(FILE_BASED_TEST_H2_DATABASE_URL);
    }

    /**
     * Creates a unique (random) {@link TestH2DataSource} using the URL {@link #IN_MEMORY_TEST_H2_DATABASE_URL}, which
     * is <code>"jdbc:h2:mem:matsTestH2DB_[randomness];DB_CLOSE_DELAY=-1"</code>.
     *
     * @return the created {@link TestH2DataSource}.
     */
    public static TestH2DataSource createInMemoryRandom() {
        return create(IN_MEMORY_TEST_H2_DATABASE_URL
                .replace("$random$", Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36)));
    }

    /**
     * Creates a {@link TestH2DataSource} using the supplied URL.
     *
     * @return the created {@link TestH2DataSource}.
     */
    public static TestH2DataSource create(String url) {
        log.info("Creating TestH2DataSource with URL [" + url + "].");
        TestH2DataSource dataSource = new TestH2DataSource();
        dataSource.setURL(url);
        dataSource.cleanDatabase();
        return dataSource;
    }

    private final JdbcDataSource _wrappedH2JdbcDataSource;

    private TestH2DataSource() {
        _wrappedH2JdbcDataSource = new JdbcDataSource();
    }

    /**
     * Cleans the test database: Runs SQL <code>"DROP ALL OBJECTS DELETE FILES"</code>.
     */
    public void cleanDatabase() {
        cleanDatabase(false);
    }

    /**
     * Cleans the test database: Runs SQL <code>"DROP ALL OBJECTS DELETE FILES"</code>, and optionally invokes
     * {@link #createDataTable()}.
     *
     * @param createDataTable
     *            whether to invoke {@link #createDataTable()} afterwards.
     */
    public void cleanDatabase(boolean createDataTable) {
        String dropSql = "DROP ALL OBJECTS DELETE FILES";
        try (Connection con = this.getConnection();
                Statement stmt = con.createStatement()) {
            stmt.execute(dropSql);
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems cleaning database by running '" + dropSql + "'.", e);
        }
        if (createDataTable) {
            createDataTable();
        }
    }

    /**
     * Creates a test "datatable", runs SQL <code>"CREATE TABLE datatable ( data VARCHAR )"</code>, using a SQL
     * Connection from <code>this</code> DataSource.
     */
    public void createDataTable() {
        try {
            try (Connection dbCon = this.getConnection()) {
                createDataTable(dbCon);
            }
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems creating the SQL 'datatable'.", e);
        }
    }

    /**
     * Creates a test "datatable", runs SQL <code>"CREATE TABLE datatable ( data VARCHAR )"</code>, using the provided
     * SQL Connection.
     */
    public static void createDataTable(Connection connection) {
        try {
            try (Statement stmt = connection.createStatement();) {
                stmt.execute("CREATE TABLE datatable (data VARCHAR NOT NULL, CONSTRAINT UC_data UNIQUE (data))");
            }
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems creating the SQL 'datatable'.", e);
        }
    }

    /**
     * Inserts the provided 'data' into the SQL Table 'datatable', using a SQL Connection from <code>this</code>
     * DataSource.
     *
     * @param data
     *            the data to insert.
     */
    public void insertDataIntoDataTable(String data) {
        try (Connection con = this.getConnection()) {
            insertDataIntoDataTable(con, data);
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems getting SQL Connection.", e);
        }
    }

    /**
     * Inserts the provided 'data' into the SQL Table 'datatable', using the provided SQL Connection.
     *
     * @param sqlConnection
     *            the SQL Connection to use to insert data.
     * @param data
     *            the data to insert.
     */
    public static void insertDataIntoDataTable(Connection sqlConnection, String data) {
        // :: Populate the SQL table with a piece of data
        try {
            PreparedStatement pStmt = sqlConnection.prepareStatement("INSERT INTO datatable VALUES (?)");
            pStmt.setString(1, data);
            pStmt.execute();
            pStmt.close();
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems fetching column 'data' from SQL Table 'datatable'.", e);
        }
    }

    /**
     * @return all rows in the 'datatable' (probably created by {@link #createDataTable()}, using a SQL Connection from
     *         <code>this</code> DataSource.
     */
    public List<String> getDataFromDataTable() {
        try (Connection con = this.getConnection()) {
            return getDataFromDataTable(con);
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems getting SQL Connection.", e);
        }
    }

    /**
     * @param sqlConnection
     *            the SQL Connection to use to fetch data.
     * @return all rows in the 'datatable' (probably created by {@link #createDataTable()}, using the provided SQL
     *         Connection - the SQL is <code>"SELECT data FROM datatable ORDER BY data"</code>.
     */
    public static List<String> getDataFromDataTable(Connection sqlConnection) {
        try {
            Statement stmt = sqlConnection.createStatement();
            List<String> ret = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SELECT data FROM datatable ORDER BY data")) {
                while (rs.next()) {
                    ret.add(rs.getString(1));
                }
            }
            stmt.close();
            return ret;
        }
        catch (SQLException e) {
            throw new TestH2DataSourceException("Got problems fetching column 'data' from SQL Table 'datatable'.", e);
        }
    }

    /**
     * A {@link RuntimeException} for use in database access methods and tests.
     */
    public static class TestH2DataSourceException extends RuntimeException {
        public TestH2DataSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Closes the database IF it is a random in-memory URL (as created by {@link #createInMemoryRandom()}, <b>note:</b>
     * this method will be picked up by Spring as a destroy-method if the instance is made available as a Bean.
     */
    public void close() {
        final String thisUrl = getUrl();
        if (thisUrl.contains(":mem:matsTestH2DB")) {
            // :: Due to the fact that there is evidently some Thread.sleep(200) involved somewhere, we do this async
            // in a separate Thread. This is OK since we use separate DataSources with random URLs for each DS instance.
            log.info("Shutting down in-mem random TestH2DataSource with url [" + thisUrl
                    + "] (performed in separate Thread to not hold back next tests).");
            Thread shutdownH2Thread = new Thread(() -> {
                try {
                    Connection con = getConnection();
                    Statement stmt = con.createStatement();
                    stmt.execute("SHUTDOWN");
                    stmt.close();
                    con.close();
                }
                catch (SQLException e) {
                    throw new AssertionError("Problems shutting down H2", e);
                }
                log.info("Shutdown of TestH2DataSource [" + thisUrl + "] finished (from previous tests).");
            }, "ShutdownThread of TestH2DataSource [" + thisUrl + "]");
            // If the VM shuts down, H2 will also shut down, probably due to a shutdown hook thread.
            // We've sometimes gotten this exception during test runs:
            // Caused by: org.h2.jdbc.JdbcSQLNonTransientConnectionException: Database is already closed (to disable
            // automatic closing at VM shutdown, add ";DB_CLOSE_ON_EXIT=FALSE" to the db URL) [90121-210]
            // So, /either/ daemon this thread, OR add that prop to the URL. Both would probably be unwise.
            shutdownH2Thread.setDaemon(true);
            shutdownH2Thread.start();
        }
    }

    // =================================================================================================
    // ======= Implementation of the non-standard methods of H2's JdbcDataSource
    // =================================================================================================

    /**
     * Get the current URL.
     *
     * @return the URL
     */
    public String getURL() {
        return _wrappedH2JdbcDataSource.getURL();
    }

    /**
     * Get the current URL. This method does the same as getURL, but this methods signature conforms the JavaBean naming
     * convention.
     *
     * @return the URL
     */
    public String getUrl() {
        return _wrappedH2JdbcDataSource.getUrl();
    }

    /**
     * Set the current URL.
     *
     * @param url
     *            the new URL
     */
    public void setURL(String url) {
        _wrappedH2JdbcDataSource.setURL(url);
    }

    /**
     * Set the current URL. This method does the same as setURL, but this methods signature conforms the JavaBean naming
     * convention.
     *
     * @param url
     *            the new URL
     */
    public void setUrl(String url) {
        _wrappedH2JdbcDataSource.setUrl(url);
    }

    /**
     * Set the current password.
     *
     * @param password
     *            the new password.
     */
    public void setPassword(String password) {
        _wrappedH2JdbcDataSource.setPassword(password);
    }

    /**
     * Set the current password in the form of a char array.
     *
     * @param password
     *            the new password in the form of a char array.
     */
    public void setPasswordChars(char[] password) {
        _wrappedH2JdbcDataSource.setPasswordChars(password);
    }

    /**
     * Get the current password.
     *
     * @return the password
     */
    public String getPassword() {
        return _wrappedH2JdbcDataSource.getPassword();
    }

    /**
     * Get the current user name.
     *
     * @return the user name
     */
    public String getUser() {
        return _wrappedH2JdbcDataSource.getUser();
    }

    /**
     * Set the current user name.
     *
     * @param user
     *            the new user name
     */
    public void setUser(String user) {
        _wrappedH2JdbcDataSource.setUser(user);
    }

    /**
     * Get the current description.
     *
     * @return the description
     */
    public String getDescription() {
        return _wrappedH2JdbcDataSource.getDescription();
    }

    /**
     * Set the description.
     *
     * @param description
     *            the new description
     */
    public void setDescription(String description) {
        _wrappedH2JdbcDataSource.setDescription(description);
    }

    // =================================================================================================
    // ======= Implementation of the interfaces XADataSource, DataSource, ConnectionPoolDataSource
    // =================================================================================================

    @Override
    public Connection getConnection() throws SQLException {
        return _wrappedH2JdbcDataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return _wrappedH2JdbcDataSource.getConnection(username, password);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface == null) {
            throw new SQLException("iface is null");
        }
        if (_wrappedH2JdbcDataSource.isWrapperFor(iface)) {
            return _wrappedH2JdbcDataSource.unwrap(iface);
        }
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        throw new SQLException("this.isWrapperFor([" + iface + "]) == false, this:[" + this + "]");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface == null) {
            return false;
        }
        if (iface.isAssignableFrom(getClass())) {
            return true;
        }
        return _wrappedH2JdbcDataSource.isWrapperFor(iface);
    }

    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        return _wrappedH2JdbcDataSource.getPooledConnection();
    }

    @Override
    public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        return _wrappedH2JdbcDataSource.getPooledConnection(user, password);
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        return _wrappedH2JdbcDataSource.getXAConnection();
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        return _wrappedH2JdbcDataSource.getXAConnection(user, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return _wrappedH2JdbcDataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        _wrappedH2JdbcDataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        _wrappedH2JdbcDataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return _wrappedH2JdbcDataSource.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return _wrappedH2JdbcDataSource.getParentLogger();
    }
}
