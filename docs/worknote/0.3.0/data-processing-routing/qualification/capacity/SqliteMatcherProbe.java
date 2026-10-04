import java.sql.*;
import java.util.*;
import org.sqlite.ProgressHandler;

/** Diagnostic SQL mechanism probe; never changes canonical application data. */
class SqliteMatcherProbe {
    static final String BASE = """
        SELECT DISTINCT r.request_id, a.canonical_row_id, a.lifecycle_id, c.row_key
        FROM temp.ioc_match_request r
        JOIN canonical_match_alias a
          ON a.artifact = ? AND a.definition_id = r.definition_id
         AND a.key_hash = r.key_hash AND a.key_canonical = r.key_canonical
        JOIN ioc_aggregate c
          ON c.id = a.canonical_row_id AND c._lifecycle_id = a.lifecycle_id
        WHERE c._valid_until_epoch_ms > ?
        ORDER BY r.request_order, a.canonical_row_id, a.lifecycle_id
        """;
    static final String CROSS = BASE.replace("JOIN canonical_match_alias", "CROSS JOIN canonical_match_alias")
        .replace("JOIN ioc_aggregate", "CROSS JOIN ioc_aggregate");
    static final String DIRECT = """
        SELECT 'ordinary' AS request_id, a.canonical_row_id, a.lifecycle_id, c.row_key
        FROM canonical_match_alias a
        JOIN ioc_aggregate c ON c.id = a.canonical_row_id AND c._lifecycle_id = a.lifecycle_id
        WHERE a.artifact = ? AND a.definition_id = ? AND a.key_hash = ? AND a.key_canonical = ?
          AND c._valid_until_epoch_ms > ?
        ORDER BY a.canonical_row_id, a.lifecycle_id
        """;
    static String hash(int i) { return String.format(Locale.ROOT, "%064x", i); }
    static String key(int i) { return "ip_address=NULL;url_match=https://ioc-" + i + ".example.test/api?q=1;host_match=NULL;hash=NULL"; }
    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static String sql(String variant) { return switch (variant) { case "current" -> BASE; case "request_first" -> CROSS; case "direct" -> DIRECT; default -> throw new IllegalArgumentException(variant); }; }

    static void stage(Connection c, String definition, String hash, String key) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS temp.ioc_match_request");
            s.execute("CREATE TEMP TABLE ioc_match_request(request_order INTEGER NOT NULL,request_id TEXT NOT NULL,definition_id TEXT NOT NULL,key_hash TEXT NOT NULL,key_canonical TEXT NOT NULL,PRIMARY KEY(request_id,definition_id,key_hash,key_canonical))");
        }
        try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO temp.ioc_match_request VALUES(0,'ordinary',?,?,?)")) {
            p.setString(1, definition); p.setString(2, hash); p.setString(3, key); p.addBatch(); p.executeBatch();
        }
    }
    static void bind(PreparedStatement p, String variant, String definition, String hash, String key) throws SQLException {
        p.setString(1,"ioc_aggregate");
        if (variant.equals("direct")) {
            p.setString(2,definition); p.setString(3,hash); p.setString(4,key); p.setLong(5,100);
        } else p.setLong(2,100);
    }
    static List<String> run(Connection c, String variant, String definition, String hash, String key) throws SQLException {
        if (!variant.equals("direct")) stage(c,definition,hash,key);
        try (PreparedStatement p = c.prepareStatement(sql(variant))) {
            bind(p,variant,definition,hash,key);
            var results = new ArrayList<String>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) results.add(r.getLong(2) + ":" + r.getLong(3) + ":" + r.getString(4));
            }
            return results;
        }
    }
    static String plan(Connection c, String variant, String definition, String hash, String key) throws SQLException {
        if (!variant.equals("direct")) stage(c,definition,hash,key);
        try (PreparedStatement p = c.prepareStatement("EXPLAIN QUERY PLAN " + sql(variant))) {
            bind(p,variant,definition,hash,key);
            List<String> rows = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(quote(r.getString(4))); }
            return "[" + String.join(",",rows) + "]";
        }
    }
    static void schema(Connection c, int n) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA cache_size=-2000"); s.execute("PRAGMA temp_store=FILE");
            s.execute("CREATE TABLE ioc_aggregate(id INTEGER PRIMARY KEY,row_key TEXT NOT NULL UNIQUE,_lifecycle_id INTEGER,_valid_until_epoch_ms INTEGER)");
            s.execute("CREATE TABLE canonical_match_alias(artifact TEXT NOT NULL,definition_id TEXT NOT NULL,key_hash TEXT NOT NULL,key_canonical TEXT NOT NULL,lifecycle_id INTEGER NOT NULL,canonical_row_id INTEGER NOT NULL,PRIMARY KEY(artifact,definition_id,key_hash,key_canonical,lifecycle_id))");
            s.execute("CREATE INDEX ix_canonical_match_alias_lookup ON canonical_match_alias(artifact,definition_id,key_hash,key_canonical)");
            s.execute("CREATE INDEX ix_canonical_match_alias_lifecycle ON canonical_match_alias(artifact,lifecycle_id)");
        }
        c.setAutoCommit(false);
        try (PreparedStatement row = c.prepareStatement("INSERT INTO ioc_aggregate VALUES(?,?,?,1000)");
             PreparedStatement alias = c.prepareStatement("INSERT INTO canonical_match_alias VALUES('ioc_aggregate','ioc-aggregate-v1',?,?,?,?)")) {
            for (int i=1; i<=n; i++) {
                row.setInt(1,i); row.setString(2,"row-"+i); row.setInt(3,i); row.addBatch();
                alias.setString(1,hash(i)); alias.setString(2,key(i)); alias.setInt(3,i); alias.setInt(4,i); alias.addBatch();
                if (i%500==0) { row.executeBatch(); alias.executeBatch(); }
            }
            row.executeBatch(); alias.executeBatch();
        }
        c.commit(); c.setAutoCommit(true);
    }
    static void checkSemantics(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO ioc_aggregate VALUES(100001,'extra-active',100001,1000),(100002,'expired',100002,100),(100003,'stale-alias',100003,1000),(100004,'hash-collision',100004,1000)");
            String h=hash(1), k=key(1);
            s.execute("INSERT INTO canonical_match_alias VALUES('ioc_aggregate','ioc-aggregate-v1','"+h+"','"+k+"',100001,100001),('ioc_aggregate','ioc-aggregate-v1','"+h+"','"+k+"',100002,100002),('ioc_aggregate','ioc-aggregate-v1','"+h+"','"+k+"',200003,100003),('ioc_aggregate','ioc-aggregate-v1','"+h+"','different-canonical',100004,100004)");
        }
        List<String> expected=List.of("1:1:row-1","100001:100001:extra-active");
        for (String variant:List.of("current","request_first","direct")) {
            if (!run(c,variant,"ioc-aggregate-v1",hash(1),key(1)).equals(expected)) throw new AssertionError("Semantic mismatch " + variant);
            if (!run(c,variant,"other-definition",hash(1),key(1)).isEmpty()) throw new AssertionError("Definition crossed");
        }
        System.out.println("{\"semanticCases\":\"active multiple, exact expiry boundary, stale lifecycle, same hash different canonical, different definition: PASS\"}");
    }
    public static void main(String[] args) throws Exception {
        boolean live=args.length>0 && args[0].equals("live");
        try (Connection c=DriverManager.getConnection(live ? "jdbc:sqlite:file:"+args[1]+"?mode=ro" : "jdbc:sqlite::memory:")) {
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT sqlite_version()")) {
                r.next(); System.out.println("{\"sqliteVersion\":"+quote(r.getString(1))+",\"javaVersion\":"+quote(System.getProperty("java.version"))+",\"liveReadOnly\":"+live+"}");
            }
            if (live) {
                String d, h, k;
                try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT definition_id,key_hash,key_canonical FROM canonical_match_alias WHERE artifact='ioc_aggregate' LIMIT 1")) {
                    if (!r.next()) throw new AssertionError("No aggregate alias");
                    d=r.getString(1); h=r.getString(2); k=r.getString(3);
                }
                for (String v:List.of("current","request_first","direct")) System.out.println("{\"variant\":"+quote(v)+",\"plan\":"+plan(c,v,d,h,k)+"}");
                return;
            }
            int n=Integer.parseInt(args[0]); schema(c,n);
            for (String v:List.of("current","request_first","direct")) {
                String eqp=plan(c,v,"ioc-aggregate-v1",hash(1),key(1));
                for (int i=0;i<8;i++) run(c,v,"ioc-aggregate-v1",hash(1),key(1));
                long[] calls={0};
                ProgressHandler.setHandler(c,1000,new ProgressHandler(){ protected int progress(){ calls[0]++; return 0; }});
                run(c,v,"ioc-aggregate-v1",hash(1),key(1));
                ProgressHandler.clearHandler(c);
                for (int repeat=0;repeat<3;repeat++) {
                    long start=System.nanoTime(); int found=0;
                    for (int i=0;i<32;i++) { int k=i%2==0?1+(i*n/32):n+1+i; found+=run(c,v,"ioc-aggregate-v1",hash(k),key(k)).size(); }
                    long elapsed=System.nanoTime()-start;
                    if (found!=16) throw new AssertionError("Hit/miss mismatch");
                    System.out.println("{\"aliases\":"+n+",\"variant\":"+quote(v)+",\"repeat\":"+repeat+",\"requests\":32,\"hits\":16,\"elapsedNanos\":"+elapsed+",\"vmStepLowerBoundSingleHit\":"+(1000*calls[0])+",\"plan\":"+eqp+"}");
                }
            }
            checkSemantics(c);
        }
    }
}
