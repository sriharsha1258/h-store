package edu.brown.benchmark.wikipedia;

import org.voltdb.VoltTable;
import org.voltdb.catalog.*;
import org.voltdb.client.Client;
import edu.brown.benchmark.BenchmarkComponent;
import edu.brown.catalog.CatalogUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;
import org.voltdb.types.TimestampType;

import edu.brown.benchmark.BenchmarkComponent;
import edu.brown.benchmark.wikipedia.data.PageHistograms;
import edu.brown.benchmark.wikipedia.data.TextHistograms;
import edu.brown.benchmark.wikipedia.data.UserHistograms;
import edu.brown.benchmark.wikipedia.util.TransactionSelector;
import org.voltdb.catalog.Table;

import org.voltdb.utils.Pair;
import edu.brown.rand.RandomDistribution;
import edu.brown.rand.RandomDistribution.Flat;
import edu.brown.rand.RandomDistribution.FlatHistogram;
import edu.brown.rand.RandomDistribution.Zipf;
//import com.oltpbenchmark.util.SQLUtil;  // ignore
import edu.brown.utils.StringUtil;

import edu.brown.benchmark.wikipedia.util.TextGenerator;
import org.voltdb.types.TimestampType; // instead of TimeUtil

/**
 * Synthetic Wikipedia Data Loader
 * @author pavlo
 * @author djellel
 */
public class WikipediaLoader extends BenchmarkComponent {
    private static final Logger LOG = Logger.getLogger(WikipediaLoader.class);

    private final int num_users;
    private final int num_pages;
    
    /**
     *  scale all table cardinalities by this factor
     */
    private final double m_scalefactor;
    
    private Random randGenerator = new Random();
    
    /**
     * UserId -> # of Revisions
     */
    private final int user_revision_ctr[];

    /**
     * PageId -> Last Revision Id
     */
    private final int page_last_rev_id[];
    
    /**
     * PageId -> Last Revision Length
     */
    private final int page_last_rev_length[];
    
    /**
     * Pair<PageNamespace, PageTitle>
     */
    private List<Pair<Integer, String>> titles = new ArrayList<Pair<Integer, String>>();
    
    /**
     * Constructor
     * @param benchmark
     * @param c
     */
    public WikipediaLoader(String[] args) {
        super(args);
        m_scalefactor = 1.0; // FIXME, scalefactor can be...
        
        this.num_users = (int) Math.round(WikipediaConstants.USERS * this.m_scalefactor);
        this.num_pages = (int) Math.round(WikipediaConstants.PAGES * this.m_scalefactor);
        
        this.user_revision_ctr = new int[this.num_users];
        Arrays.fill(this.user_revision_ctr, 0);
        
        this.page_last_rev_id = new int[this.num_pages];
        Arrays.fill(this.page_last_rev_id, -1);
        this.page_last_rev_length = new int[this.num_pages];
        Arrays.fill(this.page_last_rev_length, -1);
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("# of USERS:  " + this.num_users);
            LOG.debug("# of PAGES: " + this.num_pages);
        }
    }
    
    @Override
    public String[] getTransactionDisplayNames() {
        // IGNORE: Only needed for Client
        return new String[] {};
    }

    @Override
    public void runLoop() {
        final Catalog catalog = this.getCatalog();
        final Database catalog_db = CatalogUtil.getDatabase(catalog);
        
        try {
            // Load Data
            this.loadUsers(catalog_db);
            this.loadPages(catalog_db);
            this.loadWatchlist(catalog_db);
            this.loadRevision(catalog_db);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * USERACCTS
     */
    private void loadUsers( Database catalog_db ) {
        Table catalog_tbl = catalog_db.getTables().get(WikipediaConstants.TABLENAME_USER);
        assert(catalog_tbl != null);

//        String sql = SQLUtil.getInsertSQL(catalog_tbl);
//        PreparedStatement userInsert = this.conn.prepareStatement(sql);
        FlatHistogram<Integer> h_nameLength = new FlatHistogram<Integer>(this.randGenerator, UserHistograms.NAME_LENGTH);
        FlatHistogram<Integer> h_realNameLength = new FlatHistogram<Integer>(this.randGenerator, UserHistograms.REAL_NAME_LENGTH);
        FlatHistogram<Integer> h_revCount = new FlatHistogram<Integer>(this.randGenerator, UserHistograms.REVISION_COUNT);

        VoltTable vt = CatalogUtil.getVoltTable(catalog_tbl);
        int num_cols = catalog_tbl.getColumns().size();
        int batchSize = 0;
        int lastPercent = -1;
        for (int i = 1; i <= this.num_users; i++) {
            // The name will be prefixed with their UserId. This increases
            // the likelihood that all of our usernames are going to be unique
            // It's not a guarantee, but it's good enough...
            String name = Integer.toString(i) + TextGenerator.randomStr(randGenerator, h_nameLength.nextValue().intValue());
            String realName = TextGenerator.randomStr(randGenerator, h_realNameLength.nextValue().intValue());
            int revCount = h_revCount.nextValue().intValue();
            String password = StringUtil.repeat("*", randGenerator.nextInt(32));
            
            char eChars[] = TextGenerator.randomChars(randGenerator, randGenerator.nextInt(32) + 5);
            eChars[4 + randGenerator.nextInt(eChars.length-4)] = '@';
            String email = new String(eChars);
            
            String token = TextGenerator.randomStr(randGenerator, WikipediaConstants.TOKEN_LENGTH);
            String userOptions = "fake_longoptionslist";
            TimestampType newPassTime = new TimestampType();
            TimestampType touched = new TimestampType();

            Object row[] = new Object[num_cols];
            int param = 1;
            userInsert.setInt(param++, i);                // user_id
            userInsert.setString(param++, name);          // user_name
            userInsert.setString(param++, realName);      // user_real_name
            userInsert.setString(param++, password);      // user_password
            userInsert.setString(param++, password);      // user_newpassword
            userInsert.setString(param++, newPassTime);   // user_newpass_time
            userInsert.setString(param++, email);         // user_email
            userInsert.setString(param++, userOptions);   // user_options
            userInsert.setString(param++, touched);       // user_touched
            userInsert.setString(param++, token);         // user_token
            userInsert.setNull(param++, types[param-2]);    // user_email_authenticated
            userInsert.setNull(param++, types[param-2]);    // user_email_token
            userInsert.setNull(param++, types[param-2]);    // user_email_token_expires
            userInsert.setNull(param++, types[param-2]);    // user_registration
            userInsert.setInt(param++, revCount);         // user_editcount
            userInsert.addBatch();

            if (++batchSize % WikipediaConstants.BATCH_SIZE == 0) {
                userInsert.executeBatch();
                this.conn.commit();
                userInsert.clearBatch();
                this.addToTableCount(catalog_tbl.getName(), batchSize);
                batchSize = 0;
                if (LOG.isDebugEnabled()) {
                    int percent = (int) (((double) i / (double) this.num_users) * 100);
                    if (percent != lastPercent) LOG.debug("USERACCT (" + percent + "%)");
                    lastPercent = percent;
                }
            }
        } // FOR
        if (batchSize > 0) {
            this.addToTableCount(catalog_tbl.getName(), batchSize);
            userInsert.executeBatch();
            this.conn.commit();
            userInsert.clearBatch();
        }
        userInsert.close();
        
        if (LOG.isDebugEnabled())
            LOG.debug("Users  % " + this.num_users);
    }

    /**
     * PAGE
     */
    private void loadPages(Database catalog_db) {
        Table catalog_tbl = catalog_db.getTables().get(WikipediaConstants.TABLENAME_PAGE);
        assert(catalog_tbl != null);

        String sql = SQLUtil.getInsertSQL(catalog_tbl);
        PreparedStatement pageInsert = this.conn.prepareStatement(sql);
        
        FlatHistogram<Integer> h_titleLength = new FlatHistogram<Integer>(this.randGenerator, PageHistograms.TITLE_LENGTH);
        FlatHistogram<Integer> h_namespace = new FlatHistogram<Integer>(this.randGenerator, PageHistograms.NAMESPACE);
        FlatHistogram<String> h_restrictions = new FlatHistogram<String>(this.randGenerator, PageHistograms.RESTRICTIONS);

        int batchSize = 0;
        int lastPercent = -1;
        for (int i = 1; i <= this.num_pages; i++) {
            String title = TextGenerator.randomStr(randGenerator, h_titleLength.nextValue().intValue());
            int namespace = h_namespace.nextValue().intValue();
            String restrictions = h_restrictions.nextValue();
            double pageRandom = randGenerator.nextDouble();
            String pageTouched = TimeUtil.getCurrentTimeString14();
            
            int param = 1;
            pageInsert.setInt(param++, i);              // page_id
            pageInsert.setInt(param++, namespace);      // page_namespace
            pageInsert.setString(param++, title);       // page_title
            pageInsert.setString(param++, restrictions);// page_restrictions
            pageInsert.setInt(param++, 0);              // page_counter
            pageInsert.setInt(param++, 0);              // page_is_redirect
            pageInsert.setInt(param++, 0);              // page_is_new
            pageInsert.setDouble(param++, pageRandom);  // page_random
            pageInsert.setString(param++, pageTouched); // page_touched
            pageInsert.setInt(param++, 0);              // page_latest
            pageInsert.setInt(param++, 0);              // page_len
            pageInsert.addBatch();
            this.titles.add(Pair.of(namespace, title));

            if (++batchSize % WikipediaConstants.BATCH_SIZE == 0) {
                pageInsert.executeBatch();
                this.conn.commit();
                pageInsert.clearBatch();
                this.addToTableCount(catalog_tbl.getName(), batchSize);
                batchSize = 0;
                if (LOG.isDebugEnabled()) {
                    int percent = (int) (((double) i / (double) this.num_pages) * 100);
                    if (percent != lastPercent) LOG.debug("PAGE (" + percent + "%)");
                    lastPercent = percent;
                }
            }
        } // FOR
        if (batchSize > 0) {
            pageInsert.executeBatch();
            this.conn.commit();
            pageInsert.clearBatch();
            this.addToTableCount(catalog_tbl.getName(), batchSize);
        }
        pageInsert.close();
        if (this.getDatabaseType() == DatabaseType.POSTGRES) {
            this.updateAutoIncrement(catalog_tbl.getColumn(0), this.num_pages);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Users  % " + this.num_pages);
    }

    /**
     * WATCHLIST
     */
    private void loadWatchlist(Database catalog_db) {
        Table catalog_tbl = catalog_db.getTables().get(WikipediaConstants.TABLENAME_WATCHLIST);
        assert(catalog_tbl != null);
        
        String sql = SQLUtil.getInsertSQL(catalog_tbl, 1);
        PreparedStatement watchInsert = this.conn.prepareStatement(sql);
        
        Zipf h_numWatches = new Zipf(randGenerator, 0, this.num_pages, WikipediaConstants.NUM_WATCHES_PER_USER_SIGMA);
        Zipf h_pageId = new Zipf(randGenerator, 1, this.num_pages, WikipediaConstants.WATCHLIST_PAGE_SIGMA);

        int batchSize = 0;
        int lastPercent = -1;
        Set<Integer> userPages = new HashSet<Integer>();
        for (int user_id = 1; user_id <= this.num_users; user_id++) {
            int num_watches = h_numWatches.nextInt();
            if (LOG.isTraceEnabled())
                LOG.trace(user_id + " => " + num_watches);
            
            userPages.clear();
            for (int i = 0; i < num_watches; i++) {
                int pageId = h_pageId.nextInt();
                while (userPages.contains(pageId)) {
                    pageId = h_pageId.nextInt();
                } // WHILE
                userPages.add(pageId);
                
                Pair<Integer, String> page = this.titles.get(pageId);
                assert(page != null) : "Invalid PageId " + pageId;
                
                int param = 1;
                watchInsert.setInt(param++, user_id); // wl_user
                watchInsert.setInt(param++, page.getFirst()); // wl_namespace
                watchInsert.setString(param++, page.getSecond()); // wl_title
                watchInsert.setNull(param++, java.sql.Types.VARCHAR); // wl_notificationtimestamp
                watchInsert.addBatch();
                batchSize++;
            } // FOR

            if (batchSize >= WikipediaConstants.BATCH_SIZE) {
                watchInsert.executeBatch();
                this.conn.commit();
                watchInsert.clearBatch();
                this.addToTableCount(catalog_tbl.getName(), batchSize);
                batchSize = 0;
                if (LOG.isDebugEnabled()) {
                    int percent = (int) (((double) user_id / (double) this.num_users) * 100);
                    if (percent != lastPercent) LOG.debug("WATCHLIST (" + percent + "%)");
                    lastPercent = percent;
                }
            }
        } // FOR
        if (batchSize > 0) {
            watchInsert.executeBatch();
            watchInsert.clearBatch();
            this.conn.commit();
            this.addToTableCount(catalog_tbl.getName(), batchSize);
        }
        watchInsert.close();
        if (LOG.isDebugEnabled())
            LOG.debug("Watchlist Loaded");
    }

    /**
     * REVISIONS
     */
    private void loadRevision(Database catalog_db) {
        
        // TEXT
        Table textTable = catalog_db.getTables().get(WikipediaConstants.TABLENAME_TEXT);
        String textSQL = SQLUtil.getInsertSQL(textTable);
        PreparedStatement textInsert = this.conn.prepareStatement(textSQL);

        // REVISION
        Table revTable = this.getTableCatalog(WikipediaConstants.TABLENAME_REVISION);
        String revSQL = SQLUtil.getInsertSQL(revTable);
        PreparedStatement revisionInsert = this.conn.prepareStatement(revSQL);

        WikipediaBenchmark b = (WikipediaBenchmark)this.benchmark;
        int batchSize = 1;
        Zipf h_users = new Zipf(this.randGenerator, 1, this.num_users, WikipediaConstants.REVISION_USER_SIGMA);
        FlatHistogram<Integer> h_textLength = new FlatHistogram<Integer>(this.randGenerator, TextHistograms.TEXT_LENGTH);
        FlatHistogram<Integer> h_commentLength = b.commentLength;
        FlatHistogram<Integer> h_minorEdit = b.minorEdit;
        FlatHistogram<Integer> h_nameLength = new FlatHistogram<Integer>(this.randGenerator, UserHistograms.NAME_LENGTH);
        FlatHistogram<Integer> h_numRevisions = new FlatHistogram<Integer>(this.randGenerator, PageHistograms.REVISIONS_PER_PAGE);
        
        int rev_id = 1;
        int lastPercent = -1;
        for (int page_id = 1; page_id <= this.num_pages; page_id++) {
            // There must be at least one revision per page
            int num_revised = h_numRevisions.nextValue().intValue();
            
            // Generate what the new revision is going to be
            int old_text_length = h_textLength.nextValue().intValue();
            assert(old_text_length > 0);
            char old_text[] = TextGenerator.randomChars(randGenerator, old_text_length);
            
            for (int i = 0; i < num_revised; i++) {
                // Generate the User who's doing the revision and the Page revised
                // Makes sure that we always update their counter
                int user_id = h_users.nextInt();
                assert(user_id > 0 && user_id <= this.num_users) : "Invalid UserId '" + user_id + "'";
                this.user_revision_ctr[user_id-1]++;
                
                // Generate what the new revision is going to be
                if (i > 0) {
                    old_text = b.generateRevisionText(old_text);
                    old_text_length = old_text.length;
                }
                
                char rev_comment[] = TextGenerator.randomChars(randGenerator, h_commentLength.nextValue().intValue());

                // The REV_USER_TEXT field is usually the username, but we'll just 
                // put in gibberish for now
                char user_text[] = TextGenerator.randomChars(randGenerator, h_nameLength.nextValue().intValue());
                
                // Insert the text
                int col = 1;
                textInsert.setInt(col++, rev_id); // old_id
                textInsert.setString(col++, new String(old_text)); // old_text
                textInsert.setString(col++, "utf-8"); // old_flags
                textInsert.setInt(col++, page_id); // old_page
                textInsert.addBatch();

                // Insert the revision
                col = 1;
                revisionInsert.setInt(col++, rev_id); // rev_id
                revisionInsert.setInt(col++, page_id); // rev_page
                revisionInsert.setInt(col++, rev_id); // rev_text_id
                revisionInsert.setString(col++, new String(rev_comment)); // rev_comment
                revisionInsert.setInt(col++, user_id); // rev_user
                revisionInsert.setString(col++, new String(user_text)); // rev_user_text
                revisionInsert.setString(col++, TimeUtil.getCurrentTimeString14()); // rev_timestamp
                revisionInsert.setInt(col++, h_minorEdit.nextValue().intValue()); // rev_minor_edit
                revisionInsert.setInt(col++, 0); // rev_deleted
                revisionInsert.setInt(col++, 0); // rev_len
                revisionInsert.setInt(col++, 0); // rev_parent_id
                revisionInsert.addBatch();
                
                // Update Last Revision Stuff
                this.page_last_rev_id[page_id-1] = rev_id;
                this.page_last_rev_length[page_id-1] = old_text_length;
                rev_id++;
                batchSize++;
            } // FOR (revision)
            if (batchSize > WikipediaConstants.BATCH_SIZE) {
                textInsert.executeBatch();
                revisionInsert.executeBatch();
                this.conn.commit();
                this.addToTableCount(textTable.getName(), batchSize);
                this.addToTableCount(revTable.getName(), batchSize);
                batchSize = 0;
                
                if (LOG.isDebugEnabled()) {
                    int percent = (int) (((double) page_id / (double) this.num_pages) * 100);
                    if (percent != lastPercent) LOG.debug("REVISIONS (" + percent + "%)");
                    lastPercent = percent;
                }
            }
        } // FOR (page)
        revisionInsert.close();
        textInsert.close();
        if (this.getDatabaseType() == DatabaseType.POSTGRES) {
            this.updateAutoIncrement(textTable.getColumn(0), rev_id);
            this.updateAutoIncrement(revTable.getColumn(0), rev_id);
        }
        
        // UPDATE USER
        revTable = this.getTableCatalog(WikipediaConstants.TABLENAME_USER);
        String updateUserSql = "UPDATE " + revTable.getEscapedName() + 
                               "   SET user_editcount = ?, " +
                               "       user_touched = ? " +
                               " WHERE user_id = ?";
        PreparedStatement userUpdate = this.conn.prepareStatement(updateUserSql);
        batchSize = 0;
        for (int i = 0; i < this.num_users; i++) {
            int col = 1;
            userUpdate.setInt(col++, this.user_revision_ctr[i]);
            userUpdate.setString(col++, TimeUtil.getCurrentTimeString14());
            userUpdate.setInt(col++, i+1); // ids start at 1
            userUpdate.addBatch();
            if ((++batchSize % WikipediaConstants.BATCH_SIZE) == 0) {
                userUpdate.executeBatch();
                this.conn.commit();
                userUpdate.clearBatch();
                batchSize = 0;
            }
        } // FOR
        if (batchSize > 0) {
            userUpdate.executeBatch();
            this.conn.commit();
            userUpdate.clearBatch();
        }
        userUpdate.close();
        
        // UPDATE PAGES
        revTable = this.getTableCatalog(WikipediaConstants.TABLENAME_PAGE);
        String updatePageSql = "UPDATE " + revTable.getEscapedName() + 
                               "   SET page_latest = ?, " +
                               "       page_touched = ?, " +
                               "       page_is_new = 0, " +
                               "       page_is_redirect = 0, " +
                               "       page_len = ? " +
                               " WHERE page_id = ?";
        PreparedStatement pageUpdate = this.conn.prepareStatement(updatePageSql);
        batchSize = 0;
        for (int i = 0; i < this.num_pages; i++) {
            if (this.page_last_rev_id[i] == -1) continue;
            
            int col = 1;
            pageUpdate.setInt(col++, this.page_last_rev_id[i]);
            pageUpdate.setString(col++, TimeUtil.getCurrentTimeString14());
            pageUpdate.setInt(col++, this.page_last_rev_length[i]);
            pageUpdate.setInt(col++, i+1); // ids start at 1
            pageUpdate.addBatch();
            if ((++batchSize % WikipediaConstants.BATCH_SIZE) == 0) {
                pageUpdate.executeBatch();
                this.conn.commit();
                pageUpdate.clearBatch();
                batchSize = 0;
            }
        } // FOR
        if (batchSize > 0) {
            pageUpdate.executeBatch();
            this.conn.commit();
            pageUpdate.clearBatch();
        }
        pageUpdate.close();
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("Revision loaded");
        }
    }
   
}