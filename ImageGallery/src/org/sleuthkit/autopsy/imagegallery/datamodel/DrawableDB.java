/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.imagegallery.datamodel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.swing.SortOrder;
import static org.apache.commons.lang3.ObjectUtils.notEqual;
import org.apache.commons.lang3.StringUtils;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.datamodel.DhsImageCategory;
import org.sleuthkit.autopsy.imagegallery.FileTypeUtils;
import org.sleuthkit.autopsy.imagegallery.ImageGalleryController;
import org.sleuthkit.autopsy.imagegallery.ImageGalleryModule;
import org.sleuthkit.autopsy.imagegallery.datamodel.grouping.GroupKey;
import org.sleuthkit.autopsy.imagegallery.datamodel.grouping.GroupManager;
import org.sleuthkit.autopsy.imagegallery.datamodel.grouping.GroupSortBy;
import static org.sleuthkit.autopsy.imagegallery.datamodel.grouping.GroupSortBy.GROUP_BY_VALUE;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.CaseDbAccessManager.CaseDbAccessQueryCallback;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.DataSource;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbTransaction;
import org.sleuthkit.datamodel.TagName;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData.DbType;
import org.sleuthkit.datamodel.TskDataException;
import org.sqlite.SQLiteJDBCLoader;

/**
 * This class is the public interface to the Image Gallery SQLite database. This
 * class borrows a lot of ideas and techniques (for good or ill) from
 * SleuthkitCase
 */
public final class DrawableDB {

    private static final Logger logger = Logger.getLogger(DrawableDB.class.getName());

    //column name constants//////////////////////
    private static final String ANALYZED = "analyzed"; //NON-NLS

    private static final String OBJ_ID = "obj_id"; //NON-NLS

    private static final String HASH_SET_NAME = "hash_set_name"; //NON-NLS

    private static final String GROUPS_TABLENAME = "image_gallery_groups"; //NON-NLS
    private static final String GROUPS_SEEN_TABLENAME = "image_gallery_groups_seen"; //NON-NLS

    private final PreparedStatement insertHashSetStmt;

    private final List<PreparedStatement> preparedStatements = new ArrayList<>();

    private final PreparedStatement removeFileStmt;

    private final PreparedStatement selectHashSetStmt;

    private final PreparedStatement selectHashSetNamesStmt;

    private final PreparedStatement insertHashHitStmt;

    private final PreparedStatement updateDataSourceStmt;

    private final PreparedStatement updateFileStmt;
    private final PreparedStatement insertFileStmt;

    private final PreparedStatement pathGroupStmt;

    private final PreparedStatement nameGroupStmt;

    private final PreparedStatement created_timeGroupStmt;

    private final PreparedStatement modified_timeGroupStmt;

    private final PreparedStatement makeGroupStmt;

    private final PreparedStatement modelGroupStmt;

    private final PreparedStatement analyzedGroupStmt;

    private final PreparedStatement hashSetGroupStmt;

    private final PreparedStatement pathGroupFilterByDataSrcStmt;

    /**
     * map from {@link DrawableAttribute} to the {@link PreparedStatement} that
     * is used to select groups for that attribute
     */
    private final Map<DrawableAttribute<?>, PreparedStatement> groupStatementMap = new HashMap<>();
    private final Map<DrawableAttribute<?>, PreparedStatement> groupStatementFilterByDataSrcMap = new HashMap<>();

    private final GroupManager groupManager;

    private final Path dbPath;

    volatile private Connection con;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true); //use fairness policy

    private final Lock DBLock = rwLock.writeLock(); //using exclusing lock for all db ops for now

    // caches to make inserts / updates faster
    private Cache<String, Boolean> groupCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Object cacheLock = new Object(); // protects access to the below cache-related objects
    private boolean areCachesLoaded = false; // if true, the below caches contain valid data
    private Set<Long> hasTagCache = new HashSet<>(); // contains obj id of files with tags
    private Set<Long> hasHashCache = new HashSet<>(); // obj id of files with hash set hits
    private Set<Long> hasExifCache = new HashSet<>(); // obj id of files with EXIF (make/model)
    private int cacheBuildCount = 0; // number of tasks taht requested the caches be built
    
    
    static {//make sure sqlite driver is loaded // possibly redundant
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            logger.log(Level.SEVERE, "Failed to load sqlite JDBC driver", ex); //NON-NLS
        }
    }
    private final SleuthkitCase tskCase;
    private final ImageGalleryController controller;

    /**
     * Enum to track Image gallery db rebuild status for a data source
     */
    public enum DrawableDbBuildStatusEnum {
        UNKNOWN, /// no known status
        IN_PROGRESS, /// drawable db rebuild has been started for the data source
        COMPLETE;       /// drawable db rebuild is complete for the data source
    }

    //////////////general database logic , mostly borrowed from sleuthkitcase
    /**
     * Lock to protect against concurrent write accesses to case database and to
     * block readers while database is in write transaction. Should be utilized
     * by all db code where underlying storage supports max. 1 concurrent writer
     * MUST always call dbWriteUnLock() as early as possible, in the same thread
     * where dbWriteLock() was called
     */
    public void dbWriteLock() {
        //Logger.getLogger("LOCK").log(Level.INFO, "Locking " + rwLock.toString());
        DBLock.lock();
    }

    /**
     * Release previously acquired write lock acquired in this thread using
     * dbWriteLock(). Call in "finally" block to ensure the lock is always
     * released.
     */
    public void dbWriteUnlock() {
        //Logger.getLogger("LOCK").log(Level.INFO, "UNLocking " + rwLock.toString());
        DBLock.unlock();
    }

    /**
     * Lock to protect against read while it is in a write transaction state.
     * Supports multiple concurrent readers if there is no writer. MUST always
     * call dbReadUnLock() as early as possible, in the same thread where
     * dbReadLock() was called.
     */
    void dbReadLock() {
        DBLock.lock();
    }

    /**
     * Release previously acquired read lock acquired in this thread using
     * dbReadLock(). Call in "finally" block to ensure the lock is always
     * released.
     */
    void dbReadUnlock() {
        DBLock.unlock();
    }

    /**
     * @param dbPath the path to the db file
     *
     * @throws SQLException if there is problem creating or configuring the db
     */
    private DrawableDB(Path dbPath, ImageGalleryController controller) throws TskCoreException, SQLException, IOException {
        this.dbPath = dbPath;
        this.controller = controller;
        this.tskCase = controller.getSleuthKitCase();
        this.groupManager = controller.getGroupManager();
        Files.createDirectories(dbPath.getParent());
        if (initializeDBSchema()) {
            updateFileStmt = prepareStatement(
                    "INSERT OR REPLACE INTO drawable_files (obj_id, data_source_obj_id, path, name, created_time, modified_time, make, model, analyzed) " //NON-NLS
                    + "VALUES (?,?,?,?,?,?,?,?,?)"); //NON-NLS
            insertFileStmt = prepareStatement(
                    "INSERT OR IGNORE INTO drawable_files (obj_id, data_source_obj_id, path, name, created_time, modified_time, make, model, analyzed) " //NON-NLS
                    + "VALUES (?,?,?,?,?,?,?,?,?)"); //NON-NLS

            updateDataSourceStmt = prepareStatement(
                    "INSERT OR REPLACE INTO datasources (ds_obj_id, drawable_db_build_status) " //NON-NLS
                    + " VALUES (?,?)"); //NON-NLS

            removeFileStmt = prepareStatement("DELETE FROM drawable_files WHERE obj_id = ?"); //NON-NLS

            pathGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE path  = ? ", DrawableAttribute.PATH); //NON-NLS
            nameGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE  name  = ? ", DrawableAttribute.NAME); //NON-NLS
            created_timeGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE created_time  = ? ", DrawableAttribute.CREATED_TIME); //NON-NLS
            modified_timeGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE  modified_time  = ? ", DrawableAttribute.MODIFIED_TIME); //NON-NLS
            makeGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE make  = ? ", DrawableAttribute.MAKE); //NON-NLS
            modelGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE model  = ? ", DrawableAttribute.MODEL); //NON-NLS
            analyzedGroupStmt = prepareStatement("SELECT obj_id , analyzed FROM drawable_files WHERE analyzed = ?", DrawableAttribute.ANALYZED); //NON-NLS
            hashSetGroupStmt = prepareStatement("SELECT drawable_files.obj_id AS obj_id, analyzed FROM drawable_files ,  hash_sets , hash_set_hits  WHERE drawable_files.obj_id = hash_set_hits.obj_id AND hash_sets.hash_set_id = hash_set_hits.hash_set_id AND hash_sets.hash_set_name = ?", DrawableAttribute.HASHSET); //NON-NLS

            //add other xyzFilterByDataSrc prepared statments as we add support for filtering by DS to other groups
            pathGroupFilterByDataSrcStmt = prepareFilterByDataSrcStatement("SELECT obj_id , analyzed FROM drawable_files WHERE path  = ? AND data_source_obj_id = ?", DrawableAttribute.PATH);

            selectHashSetNamesStmt = prepareStatement("SELECT DISTINCT hash_set_name FROM hash_sets"); //NON-NLS
            insertHashSetStmt = prepareStatement("INSERT OR IGNORE INTO hash_sets (hash_set_name)  VALUES (?)"); //NON-NLS
            selectHashSetStmt = prepareStatement("SELECT hash_set_id FROM hash_sets WHERE hash_set_name = ?"); //NON-NLS

            insertHashHitStmt = prepareStatement("INSERT OR IGNORE INTO hash_set_hits (hash_set_id, obj_id) VALUES (?,?)"); //NON-NLS

            CaseDbTransaction caseDbTransaction = null;
            try {
                caseDbTransaction = tskCase.beginTransaction();
                for (DhsImageCategory cat : DhsImageCategory.values()) {
                    insertGroup(cat.getDisplayName(), DrawableAttribute.CATEGORY, caseDbTransaction);
                }
                caseDbTransaction.commit();
            } catch (TskCoreException ex) {
                if (null != caseDbTransaction) {
                    try {
                        caseDbTransaction.rollback();
                    } catch (TskCoreException ex2) {
                        logger.log(Level.SEVERE, "Error in trying to rollback transaction", ex2);
                    }
                }
                throw ex;
            }

            initializeImageList();
        } else {
            throw new TskCoreException("Failed to initialize Image Gallery db schema");
        }
    }

    /**
     * create PreparedStatement with the supplied string, and add the new
     * statement to the list of PreparedStatements used in {@link DrawableDB#closeStatements()
     *
     * @param stmtString the string representation of the sqlite statement to
     *                   prepare
     *
     * @return the prepared statement
     *
     * @throws SQLException if unable to prepare the statement
     */
    private PreparedStatement prepareStatement(String stmtString) throws SQLException {
        PreparedStatement prepareStatement = con.prepareStatement(stmtString);
        preparedStatements.add(prepareStatement);
        return prepareStatement;
    }

    /**
     * calls {@link DrawableDB#prepareStatement(java.lang.String) ,
     *  and then add the statement to the groupStatmentMap used to lookup
     * statements by the attribute/column they group on
     *
     * @param stmtString the string representation of the sqlite statement to
     *                   prepare
     * @param attr       the {@link DrawableAttribute} this query groups by
     *
     * @return the prepared statement
     *
     * @throws SQLExceptionif unable to prepare the statement
     */
    private PreparedStatement prepareStatement(String stmtString, DrawableAttribute<?> attr) throws SQLException {
        PreparedStatement prepareStatement = prepareStatement(stmtString);
        if (attr != null) {
            groupStatementMap.put(attr, prepareStatement);
        }

        return prepareStatement;
    }

    /**
     * calls {@link DrawableDB#prepareStatement(java.lang.String) ,
     *  and then add the statement to the groupStatementFilterByDataSrcMap map used to lookup
     * statements by the attribute/column they group on
     *
     * @param stmtString the string representation of the sqlite statement to
     *                   prepare
     * @param attr       the {@link DrawableAttribute} this query groups by
     *      *
     * @return the prepared statement
     *
     * @throws SQLExceptionif unable to prepare the statement
     */
    private PreparedStatement prepareFilterByDataSrcStatement(String stmtString, DrawableAttribute<?> attr) throws SQLException {
        PreparedStatement prepareStatement = prepareStatement(stmtString);
        if (attr != null) {
            groupStatementFilterByDataSrcMap.put(attr, prepareStatement);
        }

        return prepareStatement;
    }

    private void setQueryParams(PreparedStatement statement, GroupKey<?> groupKey) throws SQLException {

        statement.setObject(1, groupKey.getValue());

        if (groupKey.getDataSource().isPresent()
            && (groupKey.getAttribute() == DrawableAttribute.PATH)) {
            statement.setObject(2, groupKey.getDataSourceObjId());
        }
    }

    /**
     * Public factory method. Creates and opens a connection to a new database *
     * at the given path. If there is already a db at the path, it is checked
     * for compatibility, and deleted if it is incompatible, before a connection
     * is opened.
     *
     * @param controller
     *
     * @return A DrawableDB for the given controller.
     *
     * @throws org.sleuthkit.datamodel.TskCoreException
     */
    public static DrawableDB getDrawableDB(ImageGalleryController controller) throws TskCoreException {
        Path dbPath = ImageGalleryModule.getModuleOutputDir(controller.getAutopsyCase()).resolve("drawable.db");
        boolean hasDataSourceObjIdColumn = hasDataSourceObjIdColumn(dbPath);
        try {
            if (hasDataSourceObjIdColumn == false) {
                Files.deleteIfExists(dbPath);
            }
        } catch (IOException ex) {
            throw new TskCoreException("Error deleting old database", ex); //NON-NLS
        }

        try {
            return new DrawableDB(dbPath, controller); //NON-NLS
        } catch (SQLException ex) {
            throw new TskCoreException("SQL error creating database connection", ex); //NON-NLS
        } catch (IOException ex) {
            throw new TskCoreException("Error creating database connection", ex); //NON-NLS
        }
    }

    /**
     * Check if the db at the given path has the data_source_obj_id column. If
     * the db doesn't exist or doesn't even have the drawable_files table, this
     * method returns false.
     *
     * NOTE: This method makes an ad-hoc connection to db, which has the side
     * effect of creating the drawable.db file if it didn't already exist.
     */
    private static boolean hasDataSourceObjIdColumn(Path dbPath) throws TskCoreException {

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString()); //NON-NLS
                Statement stmt = con.createStatement();) {
            boolean tableExists = false;
            try (ResultSet results = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'");) {//NON-NLS
                while (results.next()) {
                    if ("drawable_files".equals(results.getString("name"))) {
                        tableExists = true;
                        break;
                    }
                }
            }
            if (false == tableExists) {
                return false;
            }
            try (ResultSet results = stmt.executeQuery("PRAGMA table_info('drawable_files')");) {   //NON-NLS
                while (results.next()) {
                    if ("data_source_obj_id".equals(results.getString("name"))) {
                        return true;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new TskCoreException("SQL error checking database compatibility", ex); //NON-NLS
        }
        return false;
    }

    private void setPragmas() throws SQLException {

        //this should match Sleuthkit db setupt
        try (Statement statement = con.createStatement()) {
            //reduce i/o operations, we have no OS crash recovery anyway
            statement.execute("PRAGMA synchronous = OFF;"); //NON-NLS
            //allow to query while in transaction - no need read locks
            statement.execute("PRAGMA read_uncommitted = True;"); //NON-NLS

            //TODO: do we need this?
            statement.execute("PRAGMA foreign_keys = ON"); //NON-NLS

            //TODO: test this
            statement.execute("PRAGMA journal_mode  = MEMORY"); //NON-NLS
//
            //we don't use this feature, so turn it off for minimal speed up on queries
            //this is deprecated and not recomended
            statement.execute("PRAGMA count_changes = OFF;"); //NON-NLS
            //this made a big difference to query speed
            statement.execute("PRAGMA temp_store = MEMORY"); //NON-NLS
            //this made a modest improvement in query speeds
            statement.execute("PRAGMA cache_size = 50000"); //NON-NLS
            //we never delete anything so...
            statement.execute("PRAGMA auto_vacuum = 0"); //NON-NLS
        }

        try {
            logger.log(Level.INFO, String.format("sqlite-jdbc version %s loaded in %s mode", //NON-NLS
                    SQLiteJDBCLoader.getVersion(), SQLiteJDBCLoader.isNativeMode()
                    ? "native" : "pure-java")); //NON-NLS
        } catch (Exception exception) {
            logger.log(Level.WARNING, "exception while checking sqlite-jdbc version and mode", exception); //NON-NLS
        }

    }

    /**
     * create the table and indices if they don't already exist
     *
     * @return the number of rows in the table , count > 0 indicating an
     *         existing table
     */
    private boolean initializeDBSchema() {
        try {
            if (isClosed()) {
                openDBCon();
            }
            setPragmas();

        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "problem accessing database", ex); //NON-NLS
            return false;
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS datasources " //NON-NLS
                         + "( id INTEGER PRIMARY KEY, " //NON-NLS
                         + " ds_obj_id integer UNIQUE NOT NULL, "
                         + " drawable_db_build_status VARCHAR(128) )"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "problem creating datasources table", ex); //NON-NLS
            return false;
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE TABLE  if not exists drawable_files " //NON-NLS
                         + "( obj_id INTEGER PRIMARY KEY, " //NON-NLS
                         + " data_source_obj_id INTEGER NOT NULL, "
                         + " path VARCHAR(255), " //NON-NLS
                         + " name VARCHAR(255), " //NON-NLS
                         + " created_time integer, " //NON-NLS
                         + " modified_time integer, " //NON-NLS
                         + " make VARCHAR(255), " //NON-NLS
                         + " model VARCHAR(255), " //NON-NLS
                         + " analyzed integer DEFAULT 0)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "problem creating drawable_files table", ex); //NON-NLS
            return false;
        }

        String autogenKeyType = (DbType.POSTGRESQL == tskCase.getDatabaseType()) ? "BIGSERIAL" : "INTEGER";

        // The image_gallery_groups table is created in the Case Database
        try {
            String tableSchema
                    = "( group_id " + autogenKeyType + " PRIMARY KEY, " //NON-NLS
                      + " data_source_obj_id integer DEFAULT 0, "
                      + " value VARCHAR(255) not null, " //NON-NLS
                      + " attribute VARCHAR(255) not null, " //NON-NLS
                      + " UNIQUE(data_source_obj_id, value, attribute) )"; //NON-NLS

            tskCase.getCaseDbAccessManager().createTable(GROUPS_TABLENAME, tableSchema);
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, "problem creating groups table", ex); //NON-NLS
            return false;
        }

        // The image_gallery_groups_seen table is created in the Case Database
        try {

            String tableSchema
                    = "( id " + autogenKeyType + " PRIMARY KEY, " //NON-NLS
                      + " group_id integer not null, " //NON-NLS
                      + " examiner_id integer not null, " //NON-NLS
                      + " seen integer DEFAULT 0, " //NON-NLS
                      + " UNIQUE(group_id, examiner_id),"
                      + " FOREIGN KEY(group_id) REFERENCES " + GROUPS_TABLENAME + "(group_id),"
                      + " FOREIGN KEY(examiner_id) REFERENCES  tsk_examiners(examiner_id)"
                      + " )"; //NON-NLS

            tskCase.getCaseDbAccessManager().createTable(GROUPS_SEEN_TABLENAME, tableSchema);
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, "problem creating image_gallery_groups_seen table", ex); //NON-NLS
            return false;
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE TABLE  if not exists hash_sets " //NON-NLS
                         + "( hash_set_id INTEGER primary key," //NON-NLS
                         + " hash_set_name VARCHAR(255) UNIQUE NOT NULL)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "problem creating hash_sets table", ex); //NON-NLS
            return false;
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE TABLE  if not exists hash_set_hits " //NON-NLS
                         + "(hash_set_id INTEGER REFERENCES hash_sets(hash_set_id) not null, " //NON-NLS
                         + " obj_id INTEGER REFERENCES drawable_files(obj_id) not null, " //NON-NLS
                         + " PRIMARY KEY (hash_set_id, obj_id))"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "problem creating hash_set_hits table", ex); //NON-NLS
            return false;
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE  INDEX if not exists path_idx ON drawable_files(path)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem creating path_idx", ex); //NON-NLS
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE  INDEX if not exists name_idx ON drawable_files(name)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem creating name_idx", ex); //NON-NLS
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE  INDEX if not exists make_idx ON drawable_files(make)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem creating make_idx", ex); //NON-NLS
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE  INDEX if not exists model_idx ON drawable_files(model)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem creating model_idx", ex); //NON-NLS
        }

        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE  INDEX if not exists analyzed_idx ON drawable_files(analyzed)"; //NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem creating analyzed_idx", ex); //NON-NLS
        }

        return true;
    }

    @Override
    public void finalize() throws Throwable {
        try {
            closeDBCon();
        } finally {
            super.finalize();
        }
    }

    public void closeDBCon() {
        if (con != null) {
            try {
                closeStatements();
                con.close();
            } catch (SQLException ex) {
                logger.log(Level.WARNING, "Failed to close connection to drawable.db", ex); //NON-NLS
            }
        }
        con = null;
    }

    public void openDBCon() {
        try {
            if (con == null || con.isClosed()) {
                con = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString()); //NON-NLS
            }
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Failed to open connection to drawable.db", ex); //NON-NLS
        }
    }

    public boolean isClosed() throws SQLException {
        if (con == null) {
            return true;
        }
        return con.isClosed();
    }

    /**
     * get the names of the hashsets that the given fileID belongs to
     *
     * @param fileID the fileID to get all the Hashset names for
     *
     * @return a set of hash set names, each of which the given file belongs to
     *
     * @throws TskCoreException
     *
     *
     * //TODO: this is mostly a cut and paste from *
     * AbstractContent.getHashSetNames, is there away to dedupe?
     */
    Set<String> getHashSetsForFile(long fileID) throws TskCoreException {
        Set<String> hashNames = new HashSet<>();
        ArrayList<BlackboardArtifact> artifacts = tskCase.getBlackboardArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_HASHSET_HIT, fileID);

        for (BlackboardArtifact a : artifacts) {
            BlackboardAttribute attribute = a.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SET_NAME));
            if (attribute != null) {
                hashNames.add(attribute.getValueString());
            }
        }
        return Collections.unmodifiableSet(hashNames);
    }

    /**
     * get all the hash set names used in the db
     *
     * @return a set of the names of all the hash sets that have hash set hits
     */
    public Set<String> getHashSetNames() {
        Set<String> names = new HashSet<>();
        // "SELECT DISTINCT hash_set_name FROM hash_sets"
        dbReadLock();
        try (ResultSet rs = selectHashSetNamesStmt.executeQuery();) {
            while (rs.next()) {
                names.add(rs.getString(HASH_SET_NAME));
            }
        } catch (SQLException sQLException) {
            logger.log(Level.WARNING, "failed to get hash set names", sQLException); //NON-NLS
        } finally {
            dbReadUnlock();
        }
        return names;
    }

    static private String getGroupIdQuery(GroupKey<?> groupKey) {
        // query to find the group id from attribute/value
        return String.format(" SELECT group_id FROM " + GROUPS_TABLENAME
                             + " WHERE attribute = \'%s\' AND value = \'%s\' AND data_source_obj_id = %d",
                groupKey.getAttribute().attrName.toString(),
                groupKey.getValueDisplayName(),
                (groupKey.getAttribute() == DrawableAttribute.PATH) ? groupKey.getDataSourceObjId() : 0);
    }

    /**
     * Returns true if the specified group has been any examiner
     *
     * @param groupKey
     *
     * @return
     */
    public boolean isGroupSeen(GroupKey<?> groupKey) {
        return isGroupSeenByExaminer(groupKey, -1);
    }

    /**
     * Returns true if the specified group has been seen by the specified
     * examiner
     *
     * @param groupKey   - key to identify the group
     * @param examinerId
     *
     * @return true if the examine has this group, false otherwise
     */
    public boolean isGroupSeenByExaminer(GroupKey<?> groupKey, long examinerId) {

        // Callback to process result of seen query
        class GroupSeenQueryResultProcessor extends CompletableFuture<Boolean> implements CaseDbAccessQueryCallback {

            @Override
            public void process(ResultSet resultSet) {
                try {
                    if (resultSet != null) {
                        while (resultSet.next()) {
                            complete(resultSet.getInt("count") > 0); //NON-NLS;
                            return;
                        }
                    }
                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Failed to get group seen", ex); //NON-NLS
                }
            }
        }
        // Callback to process result of seen query
        GroupSeenQueryResultProcessor queryResultProcessor = new GroupSeenQueryResultProcessor();

        try {
            String groupSeenQueryStmt = "COUNT(*) as count FROM " + GROUPS_SEEN_TABLENAME
                                        + " WHERE seen = 1 "
                                        + " AND group_id in ( " + getGroupIdQuery(groupKey) + ")"
                                        + (examinerId > 0 ? " AND examiner_id = " + examinerId : "");// query to find the group id from attribute/value 

            tskCase.getCaseDbAccessManager().select(groupSeenQueryStmt, queryResultProcessor);
            return queryResultProcessor.get();
        } catch (ExecutionException | InterruptedException | TskCoreException ex) {
            String msg = String.format("Failed to get is group seen for group key %s", groupKey.getValueDisplayName()); //NON-NLS
            logger.log(Level.WARNING, msg, ex);
        }

        return false;
    }

    /**
     * Record in the DB that the group with the given key has the given seen
     * state for the given examiner id.
     *
     * @param groupKey
     * @param seen
     * @param examinerID
     *
     * @throws TskCoreException
     */
    public void markGroupSeen(GroupKey<?> groupKey, boolean seen, long examinerID) throws TskCoreException {

        // query to find the group id from attribute/value
        String innerQuery = String.format("( SELECT group_id FROM " + GROUPS_TABLENAME
                                          + " WHERE attribute = \'%s\' AND value = \'%s\' and data_source_obj_id = %d )",
                groupKey.getAttribute().attrName.toString(),
                groupKey.getValueDisplayName(),
                groupKey.getAttribute() == DrawableAttribute.PATH ? groupKey.getDataSourceObjId() : 0);

        String insertSQL = String.format(" (group_id, examiner_id, seen) VALUES (%s, %d, %d)", innerQuery, examinerID, seen ? 1 : 0);

        if (DbType.POSTGRESQL == tskCase.getDatabaseType()) {
            insertSQL += String.format(" ON CONFLICT (group_id, examiner_id) DO UPDATE SET seen = %d", seen ? 1 : 0);
        }

        tskCase.getCaseDbAccessManager().insertOrUpdate(GROUPS_SEEN_TABLENAME, insertSQL);

    }

    public boolean removeFile(long id) {
        DrawableTransaction trans = beginTransaction();
        boolean removeFile = removeFile(id, trans);
        commitTransaction(trans, true);
        return removeFile;
    }

    public void updateFile(DrawableFile f) {
        DrawableTransaction trans = null;
        CaseDbTransaction caseDbTransaction = null;

        try {
            trans = beginTransaction();
            caseDbTransaction = tskCase.beginTransaction();
            updateFile(f, trans, caseDbTransaction);
            caseDbTransaction.commit();
            commitTransaction(trans, true);

        } catch (TskCoreException ex) {
            if (null != caseDbTransaction) {
                try {
                    caseDbTransaction.rollback();
                } catch (TskCoreException ex2) {
                    logger.log(Level.SEVERE, "Error in trying to rollback transaction", ex2); //NON-NLS
                }
            }
            if (null != trans) {
                rollbackTransaction(trans);
            }
            logger.log(Level.SEVERE, "Error updating file", ex); //NON-NLS
        }

    }

    public void insertFile(DrawableFile f, DrawableTransaction tr, CaseDbTransaction caseDbTransaction) {
        insertOrUpdateFile(f, tr, insertFileStmt, caseDbTransaction);
    }

    public void updateFile(DrawableFile f, DrawableTransaction tr, CaseDbTransaction caseDbTransaction) {
        insertOrUpdateFile(f, tr, updateFileStmt, caseDbTransaction);
    }
    
    
    /**
     * Populate caches based on current state of Case DB
     */
    public void buildFileMetaDataCache() {
        
        synchronized (cacheLock) {      
            cacheBuildCount++;
            if (areCachesLoaded == true)
                return;

            try {
                // get tags
                try (SleuthkitCase.CaseDbQuery dbQuery = tskCase.executeQuery("SELECT obj_id FROM content_tags")) {
                    ResultSet rs = dbQuery.getResultSet();
                    while (rs.next()) {
                        long id = rs.getLong("obj_id");
                        hasTagCache.add(id);
                    }
                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Error getting tags from DB", ex); //NON-NLS
                }
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Error executing query to get tags", ex); //NON-NLS
            }

            try {
                // hash sets
                try (SleuthkitCase.CaseDbQuery dbQuery = tskCase.executeQuery("SELECT obj_id FROM blackboard_artifacts WHERE artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID())) {
                    ResultSet rs = dbQuery.getResultSet();
                    while (rs.next()) {
                        long id = rs.getLong("obj_id");
                        hasHashCache.add(id);
                    }

                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Error getting hashsets from DB", ex); //NON-NLS
                }
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Error executing query to get hashsets", ex); //NON-NLS
            }

            try {
                // EXIF
                try (SleuthkitCase.CaseDbQuery dbQuery = tskCase.executeQuery("SELECT obj_id FROM blackboard_artifacts WHERE artifact_type_id = " + BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF.getTypeID())) {
                    ResultSet rs = dbQuery.getResultSet();
                    while (rs.next()) {
                        long id = rs.getLong("obj_id");
                        hasExifCache.add(id);
                    }

                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Error getting EXIF from DB", ex); //NON-NLS
                }
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Error executing query to get EXIF", ex); //NON-NLS
            }

            areCachesLoaded = true;
        }
    }
    
    /**
     * Add a file to cache of files that have EXIF data
     * @param objectID ObjId of file with EXIF
     */
    public void addExifCache(long objectID) {
        synchronized (cacheLock) {
            // bail out if we are not maintaining caches
            if (cacheBuildCount == 0)
                return;
            hasExifCache.add(objectID);
        }
    }
    
    /**
     * Add a file to cache of files that have hash set hits
     * @param objectID ObjId of file with hash set
     */
    public void addHashSetCache(long objectID) {
        synchronized (cacheLock) {
            // bail out if we are not maintaining caches
            if (cacheBuildCount == 0)
                return;
            hasHashCache.add(objectID);
        }
    }
    
    /**
     * Add a file to cache of files that have tags
     * @param objectID ObjId of file with tags
     */
    public void addTagCache(long objectID) {
         synchronized (cacheLock) {
            // bail out if we are not maintaining caches
            if (cacheBuildCount == 0)
                return;
            hasTagCache.add(objectID);
         }
    }
    
    /**
     * Free the cached case DB data
     */
    public void freeFileMetaDataCache() {
        synchronized (cacheLock) {
            // dont' free these if there is another task still using them
            if (--cacheBuildCount > 0)
                return;

            areCachesLoaded = false;
            hasTagCache.clear();
            hasHashCache.clear();
            hasExifCache.clear();
        }
    }

    /**
     * Update (or insert) a file in(to) the drawable db. Weather this is an
     * insert or an update depends on the given prepared statement. This method
     * also inserts hash set hits and groups into their respective tables for
     * the given file.
     *
     * //TODO: this is a kinda weird design, is their a better way? //TODO:
     * implement batch version -jm
     *
     * @param f    The file to insert.
     * @param tr   a transaction to use, must not be null
     * @param stmt the statement that does the actual inserting
     */
    private void insertOrUpdateFile(DrawableFile f, @Nonnull DrawableTransaction tr, @Nonnull PreparedStatement stmt, @Nonnull CaseDbTransaction caseDbTransaction) {

        if (tr.isClosed()) {
            throw new IllegalArgumentException("can't update database with closed transaction");
        }
        
        // get data from caches. Default to true and force the DB lookup if we don't have caches
        boolean hasExif = true;
        boolean hasHashSet = true;
        boolean hasTag = true;
        synchronized (cacheLock) {
            if (areCachesLoaded) {
                hasExif = hasExifCache.contains(f.getId());
                hasHashSet = hasHashCache.contains(f.getId());
                hasTag = hasTagCache.contains(f.getId());
            }
        }

        dbWriteLock();
        try {
            // "INSERT OR IGNORE/ INTO drawable_files (obj_id, data_source_obj_id, path, name, created_time, modified_time, make, model, analyzed)"
            stmt.setLong(1, f.getId());
            stmt.setLong(2, f.getAbstractFile().getDataSourceObjectId());
            stmt.setString(3, f.getDrawablePath());
            stmt.setString(4, f.getName());
            stmt.setLong(5, f.getCrtime());
            stmt.setLong(6, f.getMtime());
            if (hasExif) {
                stmt.setString(7, f.getMake());
                stmt.setString(8, f.getModel());
            } else {
                stmt.setString(7, "");
                stmt.setString(8, "");
            }
            stmt.setBoolean(9, f.isAnalyzed());
            stmt.executeUpdate();
            
            // Update the list of file IDs in memory
            addImageFileToList(f.getId());

            // Update the hash set tables
            if (hasHashSet) {
                try {
                    for (String name : f.getHashSetNames()) {

                        // "insert or ignore into hash_sets (hash_set_name)  values (?)"
                        insertHashSetStmt.setString(1, name);
                        insertHashSetStmt.executeUpdate();

                        //TODO: use nested select to get hash_set_id rather than seperate statement/query
                        //"select hash_set_id from hash_sets where hash_set_name = ?"
                        selectHashSetStmt.setString(1, name);
                        try (ResultSet rs = selectHashSetStmt.executeQuery()) {
                            while (rs.next()) {
                                int hashsetID = rs.getInt("hash_set_id"); //NON-NLS
                                //"insert or ignore into hash_set_hits (hash_set_id, obj_id) values (?,?)";
                                insertHashHitStmt.setInt(1, hashsetID);
                                insertHashHitStmt.setLong(2, f.getId());
                                insertHashHitStmt.executeUpdate();
                                break;
                            }
                        }
                    }
                } catch (TskCoreException ex) {
                    logger.log(Level.SEVERE, "failed to insert/update hash hits for file" + f.getContentPathSafe(), ex); //NON-NLS
                }
            }

            //and update all groups this file is in
            for (DrawableAttribute<?> attr : DrawableAttribute.getGroupableAttrs()) {
                // skip attributes that we do not have data for
                if ((attr == DrawableAttribute.TAGS) && (hasTag == false)) {
                    continue;
                }
                else if ((attr == DrawableAttribute.MAKE || attr == DrawableAttribute.MODEL) && (hasExif == false)) {
                    continue;
                }
                Collection<? extends Comparable<?>> vals = attr.getValue(f);
                for (Comparable<?> val : vals) {
                    if (null != val) {
                        if (attr == DrawableAttribute.PATH) {
                            insertGroup(f.getAbstractFile().getDataSource().getId(), val.toString(), attr, caseDbTransaction);
                        }
                        else {
                            insertGroup(val.toString(), attr, caseDbTransaction);
                        }
                    }
                }
            }

            // @@@ Consider storing more than ID so that we do not need to requery each file during commit
            tr.addUpdatedFile(f.getId());

        } catch (SQLException | NullPointerException | TskCoreException ex) {
            /*
             * This is one of the places where we get an error if the case is
             * closed during processing, which doesn't need to be reported here.
             */
            if (Case.isCaseOpen()) {
                logger.log(Level.SEVERE, "failed to insert/update file" + f.getContentPathSafe(), ex); //NON-NLS
            }

        } finally {
            dbWriteUnlock();
        }
    }

    /**
     * Gets all data source object ids from datasources table, and their
     * DrawableDbBuildStatusEnum
     *
     * @return map of known data source object ids, and their db status
     *
     * @throws org.sleuthkit.datamodel.TskCoreException
     */
    public Map<Long, DrawableDbBuildStatusEnum> getDataSourceDbBuildStatus() throws TskCoreException {
        Statement statement = null;
        ResultSet rs = null;
        Map<Long, DrawableDbBuildStatusEnum> map = new HashMap<>();
        dbReadLock();
        try {
            statement = con.createStatement();
            rs = statement.executeQuery("SELECT ds_obj_id, drawable_db_build_status FROM datasources "); //NON-NLS
            while (rs.next()) {
                map.put(rs.getLong("ds_obj_id"), DrawableDbBuildStatusEnum.valueOf(rs.getString("drawable_db_build_status")));
            }
        } catch (SQLException e) {
            throw new TskCoreException("SQLException while getting data source object ids", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Error closing resultset", ex); //NON-NLS
                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ex) {
                    logger.log(Level.SEVERE, "Error closing statement ", ex); //NON-NLS
                }
            }
            dbReadUnlock();
        }
        return map;
    }

    /**
     * Insert/update given data source object id and it's DB rebuild status in
     * the datasources table.
     *
     * If the object id exists in the table already, it updates the status
     *
     * @param dsObjectId data source object id to insert
     * @param status     The db build statsus for datasource.
     */
    public void insertOrUpdateDataSource(long dsObjectId, DrawableDbBuildStatusEnum status) {
        dbWriteLock();
        try {
            // "INSERT OR REPLACE INTO datasources (ds_obj_id, drawable_db_build_status) " //NON-NLS
            updateDataSourceStmt.setLong(1, dsObjectId);
            updateDataSourceStmt.setString(2, status.name());

            updateDataSourceStmt.executeUpdate();
        } catch (SQLException | NullPointerException ex) {
            logger.log(Level.SEVERE, "failed to insert/update datasources table", ex); //NON-NLS
        } finally {
            dbWriteUnlock();
        }
    }

    public DrawableTransaction beginTransaction() {
        return new DrawableTransaction();
    }

    /**
     * 
     * @param tr
     * @param notifyGM If true, notify GroupManager about the changes.
     */
    public void commitTransaction(DrawableTransaction tr, Boolean notifyGM) {
        if (tr.isClosed()) {
            throw new IllegalArgumentException("can't close already closed transaction");
        }
        tr.commit(notifyGM);
    }

    public void rollbackTransaction(DrawableTransaction tr) {
        if (tr.isClosed()) {
            throw new IllegalArgumentException("can't rollback already closed transaction");
        }
        tr.rollback();
    }

    public Boolean isFileAnalyzed(DrawableFile f) {
        return isFileAnalyzed(f.getId());
    }

    public Boolean isFileAnalyzed(long fileId) {
        dbReadLock();
        try (Statement stmt = con.createStatement();
                ResultSet analyzedQuery = stmt.executeQuery("SELECT analyzed FROM drawable_files WHERE obj_id = " + fileId)) { //NON-NLS
            while (analyzedQuery.next()) {
                return analyzedQuery.getBoolean(ANALYZED);
            }
        } catch (SQLException ex) {
            String msg = String.format("Failed to determine if file %s is finalized", String.valueOf(fileId)); //NON-NLS
            logger.log(Level.WARNING, msg, ex);
        } finally {
            dbReadUnlock();
        }

        return false;
    }

    public Boolean areFilesAnalyzed(Collection<Long> fileIds) {

        dbReadLock();
        try (Statement stmt = con.createStatement();
                //Can't make this a preprared statement because of the IN ( ... )
                ResultSet analyzedQuery = stmt.executeQuery("SELECT COUNT(analyzed) AS analyzed FROM drawable_files WHERE analyzed = 1 AND obj_id IN (" + StringUtils.join(fileIds, ", ") + ")")) { //NON-NLS
            while (analyzedQuery.next()) {
                return analyzedQuery.getInt(ANALYZED) == fileIds.size();
            }
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "problem counting analyzed files: ", ex); //NON-NLS
        } finally {
            dbReadUnlock();
        }

        return false;
    }

    public Boolean isGroupAnalyzed(GroupKey<?> gk) {
        dbReadLock();
        try {
            Set<Long> fileIDsInGroup = getFileIDsInGroup(gk);
            try {
                // In testing, this method appears to be a lot faster than doing one large select statement
                for (Long fileID : fileIDsInGroup) {
                    Statement stmt = con.createStatement();
                    ResultSet analyzedQuery = stmt.executeQuery("SELECT analyzed FROM drawable_files WHERE obj_id = " + fileID); //NON-NLS
                    while (analyzedQuery.next()) {
                        if (analyzedQuery.getInt(ANALYZED) == 0) {
                            return false;
                        }
                    }
                    return true;
                }

            } catch (SQLException ex) {
                logger.log(Level.WARNING, "problem counting analyzed files: ", ex); //NON-NLS
            }
        } catch (TskCoreException tskCoreException) {
            logger.log(Level.WARNING, "problem counting analyzed files: ", tskCoreException); //NON-NLS
        } finally {
            dbReadUnlock();
        }
        return false;
    }

    /**
     * Find and return list of all ids of files matching the specific Where
     * clause
     *
     * @param sqlWhereClause a SQL where clause appropriate for the desired
     *                       files (do not begin the WHERE clause with the word
     *                       WHERE!)
     *
     * @return a list of file ids each of which satisfy the given WHERE clause
     *
     * @throws TskCoreException
     */
    public Set<Long> findAllFileIdsWhere(String sqlWhereClause) throws TskCoreException {

        Set<Long> ret = new HashSet<>();
        dbReadLock();
        try (Statement statement = con.createStatement();
                ResultSet rs = statement.executeQuery("SELECT obj_id FROM drawable_files WHERE " + sqlWhereClause);) {
            while (rs.next()) {
                ret.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new TskCoreException("SQLException thrown when calling 'DrawableDB.findAllFileIdsWhere(): " + sqlWhereClause, e);
        } finally {

            dbReadUnlock();
        }
        return ret;
    }

    /**
     * Return the number of files matching the given clause.
     *
     * @param sqlWhereClause a SQL where clause appropriate for the desired
     *                       files (do not begin the WHERE clause with the word
     *                       WHERE!)
     *
     * @return Number of files matching the given where clause
     *
     * @throws TskCoreException
     */
    public long countFilesWhere(String sqlWhereClause) throws TskCoreException {
        dbReadLock();
        try (Statement statement = con.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS COUNT FROM drawable_files WHERE " + sqlWhereClause);) {
            return rs.getLong("COUNT");
        } catch (SQLException e) {
            throw new TskCoreException("SQLException thrown when calling 'DrawableDB.countFilesWhere(): " + sqlWhereClause, e);
        } finally {
            dbReadUnlock();
        }
    }

    /**
     * Get all the values that are in db for the given attribute.
     *
     *
     * @param <A>        The type of values for the given attribute.
     * @param groupBy    The attribute to get the values for.
     * @param sortBy     The way to sort the results. Only GROUP_BY_VAL and
     *                   FILE_COUNT are supported.
     * @param sortOrder  Sort ascending or descending.
     * @param dataSource
     *
     * @return Map of data source (or null of group by attribute ignores data sources) to list of unique group values
     *
     * @throws org.sleuthkit.datamodel.TskCoreException
     */
    @SuppressWarnings("unchecked")
    public <A extends Comparable<A>> Multimap<DataSource, A> findValuesForAttribute(DrawableAttribute<A> groupBy, GroupSortBy sortBy, SortOrder sortOrder, DataSource dataSource) throws TskCoreException {

        Multimap<DataSource, A> values = HashMultimap.create();

        switch (groupBy.attrName) {
            case ANALYZED:
            case CATEGORY:
            case HASHSET:
                //these are somewhat special cases for now as they have fixed values, or live in the main autopsy database
                //they should have special handling at a higher level of the stack.
                throw new UnsupportedOperationException();
            default:
                dbReadLock();
                //TODO: convert this to prepared statement 

                StringBuilder query = new StringBuilder("SELECT data_source_obj_id, " + groupBy.attrName.toString() + ", COUNT(*) FROM drawable_files "); //NON-NLS

                if (dataSource != null) {
                    query.append(" WHERE data_source_obj_id = ").append(dataSource.getId());
                }

                query.append(" GROUP BY data_source_obj_id, ").append(groupBy.attrName.toString());

                String orderByClause = "";

                if (sortBy == GROUP_BY_VALUE) {
                    orderByClause = " ORDER BY " + groupBy.attrName.toString();
                } else if (sortBy == GroupSortBy.FILE_COUNT) {
                    orderByClause = " ORDER BY COUNT(*)";
                }

                query.append(orderByClause);

                if (orderByClause.isEmpty() == false) {
                    String sortOrderClause = "";

                    switch (sortOrder) {
                        case DESCENDING:
                            sortOrderClause = " DESC"; //NON-NLS
                            break;
                        case ASCENDING:
                            sortOrderClause = " ASC"; //NON-NLS
                            break;
                        default:
                            orderByClause = "";
                    }
                    query.append(sortOrderClause);
                }

                try (Statement stmt = con.createStatement();
                        ResultSet results = stmt.executeQuery(query.toString())) {
                    while (results.next()) {
                        /*
                         * I don't like that we have to do this cast to A here,
                         * but can't think of a better alternative at the
                         * momment unless something has gone seriously wrong, we
                         * know this should be of type A even if JAVA doesn't
                         */
                        values.put(tskCase.getDataSource(results.getLong("data_source_obj_id")),
                                (A) results.getObject(groupBy.attrName.toString()));
                    }
                } catch (SQLException ex) {
                    if (!(ex.getCause() instanceof java.lang.InterruptedException)) {

                        /* It seems like this originaly comes out of c3p0 when
                         * its thread is intereupted (cancelled because of
                         * regroup). It should be safe to just swallow this and
                         * move on.
                         *
                         * see
                         * https://sourceforge.net/p/c3p0/mailman/c3p0-users/thread/EBB32BB8-6487-43AF-B291-9464C9051869@mchange.com/
                         */
                        throw new TskCoreException("Unable to get values for attribute", ex); //NON-NLS
                    }
                } catch (TskDataException ex) {
                    throw new TskCoreException("Unable to get values for attribute", ex); //NON-NLS
                } finally {
                    dbReadUnlock();
                }
        }

        return values;
    }

    /**
     * Insert new group into DB
     *
     * @param value             Value of the group (unique to the type)
     * @param groupBy           Type of the grouping (CATEGORY, MAKE, etc.)
     * @param caseDbTransaction transaction to use for CaseDB insert/updates
     */
    private void insertGroup(final String value, DrawableAttribute<?> groupBy, CaseDbTransaction caseDbTransaction) {
        insertGroup(0, value, groupBy, caseDbTransaction);
    }

    /**
     * Insert new group into DB
     *
     * @param ds_obj_id         data source object id
     * @param value             Value of the group (unique to the type)
     * @param groupBy           Type of the grouping (CATEGORY, MAKE, etc.)
     * @param caseDbTransaction transaction to use for CaseDB insert/updates
     */
    private void insertGroup(long ds_obj_id, final String value, DrawableAttribute<?> groupBy, CaseDbTransaction caseDbTransaction) {
        // don't waste DB round trip if we recently added it
        String cacheKey = Long.toString(ds_obj_id) + "_" + value + "_" + groupBy.getDisplayName();
        if (groupCache.getIfPresent(cacheKey) != null) 
            return;
        
        try {
            String insertSQL = String.format(" (data_source_obj_id, value, attribute) VALUES (%d, \'%s\', \'%s\')",
                    ds_obj_id, value, groupBy.attrName.toString());

            if (DbType.POSTGRESQL == tskCase.getDatabaseType()) {
                insertSQL += "ON CONFLICT DO NOTHING";
            }
            tskCase.getCaseDbAccessManager().insert(GROUPS_TABLENAME, insertSQL, caseDbTransaction);
            groupCache.put(cacheKey, Boolean.TRUE);
        } catch (TskCoreException ex) {
            // Don't need to report it if the case was closed
            if (Case.isCaseOpen()) {
                logger.log(Level.SEVERE, "Unable to insert group", ex); //NON-NLS
            }
        }
    }

    /**
     * @param id the obj_id of the file to return
     *
     * @return a DrawableFile for the given obj_id
     *
     * @throws TskCoreException if unable to get a file from the currently open
     *                          {@link SleuthkitCase}
     */
    public DrawableFile getFileFromID(Long id) throws TskCoreException {
        try {
            AbstractFile f = tskCase.getAbstractFileById(id);
            return DrawableFile.create(f,
                    areFilesAnalyzed(Collections.singleton(id)), isVideoFile(f));
        } catch (IllegalStateException ex) {
            logger.log(Level.SEVERE, "there is no case open; failed to load file with id: {0}", id); //NON-NLS
            throw new TskCoreException("there is no case open; failed to load file with id: " + id, ex);
        }
    }

    public Set<Long> getFileIDsInGroup(GroupKey<?> groupKey) throws TskCoreException {

        if (groupKey.getAttribute().isDBColumn == false) {
            switch (groupKey.getAttribute().attrName) {
                case MIME_TYPE:
                    return groupManager.getFileIDsWithMimeType((String) groupKey.getValue());
                case CATEGORY:
                    return groupManager.getFileIDsWithCategory((DhsImageCategory) groupKey.getValue());
                case TAGS:
                    return groupManager.getFileIDsWithTag((TagName) groupKey.getValue());
            }
        }
        Set<Long> files = new HashSet<>();
        dbReadLock();
        try {
            PreparedStatement statement = getGroupStatment(groupKey);
            setQueryParams(statement, groupKey);

            try (ResultSet valsResults = statement.executeQuery()) {
                while (valsResults.next()) {
                    files.add(valsResults.getLong(OBJ_ID));
                }
            }
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "failed to get file for group:" + groupKey.getAttribute() + " == " + groupKey.getValue(), ex); //NON-NLS
        } finally {
            dbReadUnlock();
        }

        return files;
    }

    private void closeStatements() throws SQLException {
        for (PreparedStatement pStmt : preparedStatements) {
            pStmt.close();
        }
    }

    private PreparedStatement getGroupStatment(GroupKey<?> groupKey) {
        DrawableAttribute<?> groupBy = groupKey.getAttribute();
        if ((groupBy == DrawableAttribute.PATH) && groupKey.getDataSource().isPresent()) {

            return this.groupStatementFilterByDataSrcMap.get(groupBy);
        }

        return groupStatementMap.get(groupBy);
    }

    public long countAllFiles() throws TskCoreException {
        return countAllFiles(null);
    }

    public long countAllFiles(DataSource dataSource) throws TskCoreException {
        if (null != dataSource) {
            return countFilesWhere(" data_source_obj_id = ");
        } else {
            return countFilesWhere(" 1 ");
        }
    }

    /**
     * delete the row with obj_id = id.
     *
     * @param id the obj_id of the row to be deleted
     *
     * @return true if a row was deleted, 0 if not.
     */
    public boolean removeFile(long id, DrawableTransaction tr) {
        if (tr.isClosed()) {
            throw new IllegalArgumentException("can't update database with closed transaction");
        }
        int valsResults = 0;
        dbWriteLock();

        try {
            // Update the list of file IDs in memory
            removeImageFileFromList(id);

            //"delete from drawable_files where (obj_id = " + id + ")"
            removeFileStmt.setLong(1, id);
            removeFileStmt.executeUpdate();
            tr.addRemovedFile(id);

            //TODO: delete from hash_set_hits table also...
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "failed to delete row for obj_id = " + id, ex); //NON-NLS
        } finally {
            dbWriteUnlock();
        }

        //indicates succesfull removal of 1 file
        return valsResults == 1;

    }

    public class MultipleTransactionException extends IllegalStateException {

        public MultipleTransactionException() {
            super("cannot have more than one open transaction");//NON-NLS
        }
    }

    /**
     * For performance reasons, keep a list of all file IDs currently in the
     * drawable database. Otherwise the database is queried many times to
     * retrieve the same data.
     */
    @GuardedBy("fileIDlist")
    private final Set<Long> fileIDsInDB = new HashSet<>();

    public boolean isInDB(Long id) {
        synchronized (fileIDsInDB) {
            return fileIDsInDB.contains(id);
        }
    }

    private void addImageFileToList(Long id) {
        synchronized (fileIDsInDB) {
            fileIDsInDB.add(id);
        }
    }

    private void removeImageFileFromList(Long id) {
        synchronized (fileIDsInDB) {
            fileIDsInDB.remove(id);
        }
    }

    public int getNumberOfImageFilesInList() {
        synchronized (fileIDsInDB) {
            return fileIDsInDB.size();
        }
    }

    private void initializeImageList() {
        synchronized (fileIDsInDB) {
            dbReadLock();
            try (Statement stmt = con.createStatement();
                    ResultSet analyzedQuery = stmt.executeQuery("select obj_id from drawable_files");) {
                while (analyzedQuery.next()) {
                    addImageFileToList(analyzedQuery.getLong(OBJ_ID));
                }
            } catch (SQLException ex) {
                logger.log(Level.WARNING, "problem loading file IDs: ", ex); //NON-NLS
            } finally {
                dbReadUnlock();
            }
        }
    }

    /**
     * For performance reasons, keep the file type in memory
     */
    private final Map<Long, Boolean> videoFileMap = new ConcurrentHashMap<>();

    /**
     * is this File a video file?
     *
     * @param f check if this file is a video. will return false for null file.
     *
     * @return returns true if this file is a video as determined by {@link ImageGalleryModule#isVideoFile(org.sleuthkit.datamodel.AbstractFile)
     *         } but caches the result. returns false if passed a null AbstractFile
     */
    public boolean isVideoFile(AbstractFile f) {
        return isNull(f) ? false
                : videoFileMap.computeIfAbsent(f.getId(), id -> FileTypeUtils.hasVideoMIMEType(f));
    }

    /**
     * get the number of files with the given category.
     *
     * NOTE: although the category data is stored in autopsy as Tags, this
     * method is provided on DrawableDb to provide a single point of access for
     * ImageGallery data.
     *
     * //TODO: think about moving this and similar methods that don't actually
     * get their data form the drawabledb to a layer wrapping the drawable db:
     * something like ImageGalleryCaseData?
     *
     * @param cat the category to count the number of files for
     *
     * @return the number of the with the given category
     */
    public long getCategoryCount(DhsImageCategory cat) {
        try {
            TagName tagName = controller.getTagsManager().getTagName(cat);
            if (nonNull(tagName)) {
                return tskCase.getContentTagsByTagName(tagName).stream()
                        .map(ContentTag::getContent)
                        .map(Content::getId)
                        .filter(this::isInDB)
                        .count();
            }
        } catch (IllegalStateException ex) {
            logger.log(Level.WARNING, "Case closed while getting files"); //NON-NLS
        } catch (TskCoreException ex1) {
            logger.log(Level.SEVERE, "Failed to get content tags by tag name.", ex1); //NON-NLS
        }
        return -1;

    }

    /**
     * get the number of files in the given set that are uncategorized(Cat-0).
     *
     * NOTE: although the category data is stored in autopsy as Tags, this
     * method is provided on DrawableDb to provide a single point of access for
     * ImageGallery data.
     *
     * //TODO: think about moving this and similar methods that don't actually
     * get their data form the drawabledb to a layer wrapping the drawable db:
     * something like ImageGalleryCaseData?
     *
     * @param fileIDs the the files ids to count within
     *
     * @return the number of files in the given set with Cat-0
     */
    public long getUncategorizedCount(Collection<Long> fileIDs) throws TskCoreException {

        // if the fileset is empty, return count as 0
        if (fileIDs.isEmpty()) {
            return 0;
        }

        // get a comma seperated list of TagName ids for non zero categories
        DrawableTagsManager tagsManager = controller.getTagsManager();

        String catTagNameIDs = tagsManager.getCategoryTagNames().stream()
                .filter(tagName -> notEqual(tagName.getDisplayName(), DhsImageCategory.ZERO.getDisplayName()))
                .map(TagName::getId)
                .map(Object::toString)
                .collect(Collectors.joining(",", "(", ")"));

        String fileIdsList = "(" + StringUtils.join(fileIDs, ",") + " )";

        //count the file ids that are in the given list and don't have a non-zero category assigned to them.
        String name
                = "SELECT COUNT(obj_id) as obj_count FROM tsk_files where obj_id IN " + fileIdsList //NON-NLS
                  + " AND obj_id NOT IN (SELECT obj_id FROM content_tags WHERE content_tags.tag_name_id IN " + catTagNameIDs + ")"; //NON-NLS
        try (SleuthkitCase.CaseDbQuery executeQuery = tskCase.executeQuery(name);
                ResultSet resultSet = executeQuery.getResultSet();) {
            while (resultSet.next()) {
                return resultSet.getLong("obj_count"); //NON-NLS
            }
        } catch (SQLException ex) {
            throw new TskCoreException("Error getting category count.", ex); //NON-NLS
        }

        return -1;
    }

    /**
     * inner class that can reference access database connection
     */
    public class DrawableTransaction {

        private final Set<Long> updatedFiles;

        private final Set<Long> removedFiles;

        private boolean closed = false;

        /**
         * factory creation method
         *
         * @param con the {@link  ava.sql.Connection}
         *
         * @return a LogicalFileTransaction for the given connection
         *
         * @throws SQLException
         */
        private DrawableTransaction() {
            this.updatedFiles = new HashSet<>();
            this.removedFiles = new HashSet<>();
            //get the write lock, released in close()
            dbWriteLock();
            try {
                con.setAutoCommit(false);

            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "failed to set auto-commit to to false", ex); //NON-NLS
            }

        }

        synchronized public void rollback() {
            if (!closed) {
                try {
                    con.rollback();
                    updatedFiles.clear();
                } catch (SQLException ex1) {
                    logger.log(Level.SEVERE, "Exception while attempting to rollback!!", ex1); //NON-NLS
                } finally {
                    close();
                }
            }
        }

        /**
         * Commit changes that happened during this transaction
         * 
         * @param notifyGM If true, notify GroupManager about the changes. 
         */
        synchronized private void commit(Boolean notifyGM) {
            if (!closed) {
                try {
                    con.commit();
                    // make sure we close before we update, bc they'll need locks
                    close();

                    if (notifyGM) {
                        if (groupManager != null) {
                            groupManager.handleFileUpdate(updatedFiles);
                            groupManager.handleFileRemoved(removedFiles);
                        }
                    }
                } catch (SQLException ex) {
                    if (Case.isCaseOpen()) {
                        logger.log(Level.SEVERE, "Error commiting drawable.db.", ex); //NON-NLS
                    } else {
                        logger.log(Level.WARNING, "Error commiting drawable.db - case is closed."); //NON-NLS
                    }
                    rollback();
                }
            }
        }

        synchronized private void close() {
            if (!closed) {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ex) {
                    if (Case.isCaseOpen()) {
                        logger.log(Level.SEVERE, "Error setting auto-commit to true.", ex); //NON-NLS
                    } else {
                        logger.log(Level.SEVERE, "Error setting auto-commit to true - case is closed"); //NON-NLS
                    }
                } finally {
                    closed = true;
                    dbWriteUnlock();
                }
            }
        }

        synchronized public Boolean isClosed() {
            return closed;
        }

        synchronized private void addUpdatedFile(Long f) {
            updatedFiles.add(f);
        }

        synchronized private void addRemovedFile(long id) {
            removedFiles.add(id);
        }
    }
}
