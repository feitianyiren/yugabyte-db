// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.cql;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.UnauthorizedException;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.YBTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.yb.AssertionWrappers.assertEquals;

@RunWith(value=YBTestRunner.class)
public class TestAuthorizationEnforcement extends BaseAuthenticationCQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(org.yb.cql.TestRoles.class);

  private static final long PERMISSIONS_CACHE_TIME_MSECS = 100;
  // Time to sleep. Used to give the clients enough time to update their permissions cache.
  private static final long TIME_SLEEP_MS = PERMISSIONS_CACHE_TIME_MSECS * 3;

  // Value that we insert into the table.
  private static final int VALUE = 5;

  // Type of resources.
  private static final String ALL_KEYSPACES = "ALL KEYSPACES";
  private static final String KEYSPACE = "KEYSPACE";
  private static final String TABLE = "TABLE";
  private static final String ALL_ROLES = "ALL ROLES";
  private static final String ROLE = "ROLE";

  // Used for GRANT/REVOKE roles.
  private static final String GRANT = "grant";
  private static final String REVOKE = "revoke";

  // Permissions.
  private static final String ALL = "ALL";
  private static final String ALTER = "ALTER";
  private static final String AUTHORIZE = "AUTHORIZE";
  private static final String CREATE = "CREATE";
  private static final String DESCRIBE = "DESCRIBE";
  private static final String DROP = "DROP";
  private static final String MODIFY = "MODIFY";
  private static final String SELECT = "SELECT";
  // Permissions in the same order as in catalog_manager.cc.
  private static final List<String> ALL_PERMISSIONS =
      Arrays.asList(ALTER, AUTHORIZE, CREATE, DESCRIBE, DROP, MODIFY, SELECT);

  // Session using 'cassandra' role.
  private Session s = null;

  // Session using the created role.
  private Session s2;

  private String username;
  private String anotherUsername;
  private String password;
  private String keyspace;
  private String anotherKeyspace;
  private String table;
  private String anotherTable;

  @Rule
  public TestName testName = new TestName();

  @BeforeClass
  public static void SetUpBeforeClass() throws Exception {
    BaseCQLTest.tserverArgs = Arrays.asList("--use_cassandra_authentication=true",
        "--update_permissions_cache_msecs=" + PERMISSIONS_CACHE_TIME_MSECS);
    BaseCQLTest.setUpBeforeClass();
  }

  @Before
  public void setupSession() throws Exception {
    if (s == null) {
      s = getDefaultSession();
    }

    String name = Integer.toString(Math.abs(testName.getMethodName().hashCode()));

    username = "role_" + name;
    password = "password_"+ name;
    testCreateRoleHelperWithSession(username, password, true, false, false, s);

    s2 = getSession(username, password);

    keyspace = "keyspace_" + name;
    table = "table_" + name;

    s.execute("CREATE KEYSPACE " + keyspace);

    anotherUsername = username + "_2";
    anotherKeyspace = keyspace + "_2";
    anotherTable = table + "_2";

    if (testName.getMethodName().startsWith("testGrantPermission") ||
        testName.getMethodName().startsWith("testRevokePermission")) {
      testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    }
  }

  @After
  public void cleanup() throws Exception {
    String name = Integer.toString(Math.abs(testName.getMethodName().hashCode()));
    s2.close();
    keyspace = "keyspace_" + name;

    // Get all the tables in keyspace if any.
    ResultSet rs = s.execute(String.format(
        "SELECT table_name FROM system_schema.tables WHERE keyspace_name = '%s'", keyspace));

    List<Row> tables = rs.all();
    // Delete all the tables.
    for (Row table : tables) {
      s.execute(String.format("DROP TABLE %s.%s", keyspace, table.getString("table_name")));
    }

    // Delete the keyspace.
    s.execute("DROP KEYSPACE IF EXISTS " + keyspace);
  }

  private List<String> getAllPermissionsExcept(List<String> exceptions) {
    List<String> permissions = new ArrayList<String>();
    for (String permission : ALL_PERMISSIONS) {
      if (!exceptions.contains(permission)) {
        permissions.add(permission);
      }
    }
    return permissions;
  }

  private void revokePermissionNoSleep(String permission, String resourceType, String resource,
                                       String role) throws Exception {
    s.execute(
        String.format("REVOKE %s ON %s %s FROM %s", permission, resourceType, resource,role));
  }

  private void revokePermission(String permission, String resourceType, String resource,
                                String role) throws Exception {
    revokePermissionNoSleep(permission, resourceType, resource, role);
    Thread.sleep(TIME_SLEEP_MS);
  }

  private void grantPermissionNoSleep(String permission, String resourceType, String resource,
                                      String role) throws Exception {
    s.execute(String.format("GRANT %s ON %s %s TO %s", permission, resourceType, resource, role));
  }

  private void grantPermission(String permission, String resourceType, String resource,
                               String role) throws Exception {
    grantPermissionNoSleep(permission, resourceType, resource, role);
    // Wait so that the clients' permissions cache is updated.
    Thread.sleep(TIME_SLEEP_MS);
  }

  private void grantAllPermissionsExcept(List<String> exceptions, String resourceType,
                                         String resource, String role) throws Exception {
    List<String> permissions = getAllPermissionsExcept(exceptions);
    for (String permission : permissions) {
      grantPermissionNoSleep(permission, resourceType, resource, role);
    }
    Thread.sleep(TIME_SLEEP_MS);
  }

  private void grantAllPermission(String resourceType, String resource, String role)
      throws Exception {
    grantPermission(ALL, resourceType, resource, role);
  }

  private void grantPermissionOnAllKeyspaces(String permission, String role) throws Exception {
    grantPermission(permission, ALL_KEYSPACES, "", role);
  }

  private void grantPermissionOnAllRoles(String permission, String role) throws Exception {
    grantPermission(permission, ALL_ROLES, "", role);
  }

  private void verifyKeyspaceExists(String keyspaceName) throws Exception {
    ResultSet rs = s.execute(String.format(
        "SELECT * FROM system_schema.keyspaces WHERE keyspace_name = '%s'", keyspaceName));
    List<Row> list = rs.all();
    assertEquals(1, list.size());
  }

  private void createKeyspaceAndVerify(Session session, String keyspaceName) throws Exception {
    session.execute("CREATE KEYSPACE " + keyspaceName);
    verifyKeyspaceExists(keyspaceName);
  }

  private void deleteKeyspaceAndVerify(Session session, String keyspaceName) throws Exception {
    verifyKeyspaceExists(keyspaceName);

    session.execute("DROP KEYSPACE " + keyspaceName);

    ResultSet rs = s.execute(String.format(
        "SELECT * FROM system_schema.keyspaces WHERE keyspace_name = '%s'", keyspaceName));
    List<Row> list = rs.all();
    assertEquals(0, list.size());
  }

  private void verifyTableExists(String keyspaceName, String tableName) {
    // Verify that the table was created.
    ResultSet rs = s.execute(String.format(
        "SELECT * FROM system_schema.tables WHERE keyspace_name = '%s' AND table_name = '%s'",
        keyspaceName, tableName));

    List<Row> list = rs.all();
    assertEquals(1, list.size());
  }

  private void createTableAndVerify(Session session, String keyspaceName, String tableName)
      throws Exception {
    // Now, username should be able to create the table.
    session.execute(String.format("CREATE TABLE %s.%s (h int, v int, PRIMARY KEY(h))",
        keyspaceName, tableName));

    s.execute("USE " + keyspaceName);
    verifyTableExists(keyspaceName, tableName);
  }

  private void deleteTableAndVerify(Session session, String keyspaceName, String tableName)
    throws Exception {
    verifyTableExists(keyspaceName, tableName);
    session.execute(String.format("DROP TABLE %s.%s ", keyspaceName, tableName));

    ResultSet rs = s.execute(String.format(
        "SELECT * FROM system_schema.tables WHERE keyspace_name = '%s' AND table_name = '%s'",
        keyspaceName, tableName));

    List<Row> list = rs.all();
    assertEquals(0, list.size());
  }

  private void verifyRow(Session session, String keyspaceName, String tableName, int expectedValue)
      throws Exception {

    ResultSet rs = session.execute(String.format("SELECT * FROM %s.%s", keyspaceName, table));
    List<Row> rows = rs.all();
    assertEquals(1, rows.size());
    assertEquals(VALUE, rows.get(0).getInt("h"));
    assertEquals(expectedValue, rows.get(0).getInt("v"));
  }

  private void selectAndVerify(Session session, String keyspaceName, String tableName)
    throws Exception {
    verifyRow(session, keyspaceName, tableName, VALUE);
  }

  private void insertRow(Session session, String keyspaceName, String tableName)
    throws Exception {

    session.execute(String.format("INSERT INTO %s.%s (h, v) VALUES (%d, %d)",
        keyspaceName, tableName, VALUE, VALUE));

    // We always verify by using the cassandra role.
    selectAndVerify(s, keyspaceName, tableName);
  }

  private void updateRowAndVerify(Session session, String keyspaceName, String tableName)
    throws Exception {

    session.execute(String.format("UPDATE %s.%s SET v = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));

    verifyRow(s, keyspaceName, tableName, VALUE + 1);
  }

  private void truncateTableAndVerify(Session session, String keyspaceName, String tableName)
      throws Exception {
    s2.execute(String.format("TRUNCATE %s.%s", keyspaceName, tableName));

    ResultSet rs = s.execute(String.format("SELECT * FROM %s.%s", keyspaceName, tableName));
    assertEquals(0, rs.all().size());
  }

  private void createTableAndInsertRecord(Session session, String keyspaceName, String tableName)
      throws Exception {
    createTableAndVerify(session, keyspaceName, tableName);
    insertRow(session, keyspaceName, tableName);
  }

  @Test
  public void testCreateKeyspaceWithoutPermissions() throws Exception {
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("CREATE KEYSPACE %s_2", keyspace));
  }

  @Test
  public void testCreateKeyspaceWithWrongPermissions() throws Exception {
    // Grant all the permissions except CREATE.
    grantAllPermissionsExcept(Arrays.asList(CREATE, DESCRIBE, AUTHORIZE),
        ALL_KEYSPACES, "", username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("CREATE KEYSPACE %s_2", keyspace));
  }

  @Test
  public void testCreateKeyspaceWithCreatePermission() throws Exception {
    // Grant CREATE permission.
    grantPermissionOnAllKeyspaces(CREATE, username);

    createKeyspaceAndVerify(s2, keyspace + "_2");
  }

  @Test
  public void testCreateKeyspaceWithAllPermissions() throws Exception {
    // Grant ALL permissions.
    grantPermissionOnAllKeyspaces(ALL, username);

    createKeyspaceAndVerify(s2, keyspace + "_2");
  }

  @Test
  public void testSuperuserCanCreateKeyspace() throws Exception {
    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    createKeyspaceAndVerify(s2, keyspace + "_2");
  }

  @Test
  public void testDeleteKeyspaceWithNoPermissions() throws Exception {
    thrown.expect(UnauthorizedException.class);
    s2.execute("DROP KEYSPACE " + keyspace);
  }

  @Test
  public void testDeleteKeyspaceWithWrongPermissions() throws Exception {
    grantAllPermissionsExcept(Arrays.asList(DROP, DESCRIBE, AUTHORIZE), KEYSPACE, keyspace,
        username);

    thrown.expect(UnauthorizedException.class);
    s2.execute("DROP KEYSPACE " + keyspace);
  }

  @Test
  public void testDeleteKeyspaceWithDropPermissionOnDifferentKeyspace() throws Exception {
    createKeyspaceAndVerify(s, anotherKeyspace);

    grantPermission(DROP, KEYSPACE, anotherKeyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute("DROP KEYSPACE " + keyspace);
  }

  @Test
  public void testDeleteKeyspaceWithDropPermission() throws Exception {
    // Grant DROP permission on this test's keyspace.
    grantPermission(DROP, KEYSPACE, keyspace, username);

    deleteKeyspaceAndVerify(s2, keyspace);
  }

  @Test
  public void testDeleteKeyspaceWithDropPermissionOnAllKeyspaces() throws Exception {
    // Grant DROP permission on all keyspaces.
    grantPermissionOnAllKeyspaces(DROP, username);

    deleteKeyspaceAndVerify(s2, keyspace);
  }

  @Test
  public void testSuperuserCanDeleteKeyspace() throws Exception {
    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    deleteKeyspaceAndVerify(s2, keyspace);
  }

  @Test
  public void testCreateTableWithoutPermissions() throws Exception {
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("CREATE table %s.%s (h int, primary key(h))", keyspace, table));
  }

  @Test
  public void testCreateTableWithWrongPermissions() throws Exception {
    // Grant all the permissions except CREATE.
    grantAllPermissionsExcept(Arrays.asList(CREATE, DESCRIBE, AUTHORIZE), KEYSPACE, keyspace,
        username);

    // username shouldn't be able to create a table.
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("CREATE table %s.%s (h int, primary key(h))", keyspace, table));
  }

  @Test
  public void testCreateTableWithCreatePermission() throws Exception {
    // Grant CREATE permission on the keyspace.
    grantPermission(CREATE, KEYSPACE, keyspace, username);

    createTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanCreateTable() throws Exception {
    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    createTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testDeleteTableWithNoPermissions() throws Exception {
    createTableAndVerify(s, keyspace, table);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("DROP TABLE %s.%s", keyspace, table));
  }

  @Test
  public void testDeleteTableWithWrongPermissions() throws Exception {
    createTableAndVerify(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(DROP, DESCRIBE, AUTHORIZE), TABLE,
        keyspace + "." + table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("DROP TABLE %s.%s", keyspace, table));
  }

  @Test
  public void testDeleteTableWithDropPermissionOnDifferentKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);

    createKeyspaceAndVerify(s, anotherKeyspace);

    grantPermission(DROP, KEYSPACE, anotherKeyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("DROP TABLE %s.%s", keyspace, table));
  }

  @Test
  public void testDeleteTableWithDropPermissionOnDifferentTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    createTableAndVerify(s, keyspace, anotherTable);
    grantPermission(DROP, TABLE, keyspace + "." + anotherTable, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("DROP TABLE %s.%s", keyspace, table));
  }

  @Test
  public void testDeleteTableWithDropPermissionOnKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);

    // Grant DROP permission on this test's keyspace.
    grantPermission(DROP, KEYSPACE, keyspace, username);

    deleteTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testDeleteTableWithDropPermissionOnTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    // Grant DROP permission on this test's keyspace.
    grantPermission(DROP, TABLE, table, username);

    deleteTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testDeleteTableWithDropPermissionOnAllKeyspaces() throws Exception {
    createTableAndVerify(s, keyspace, table);

    // Grant DROP permission on all keyspaces.
    grantPermissionOnAllKeyspaces(DROP, username);

    deleteTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanDeleteTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    deleteTableAndVerify(s2, keyspace, table);
  }

  private void testStatementWithNoPermissions() throws Exception {

  }

  /*
   * SELECT statements tests.
   */

  @Test
  public void testSelectStatementWithNoPermissions() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithWrongPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(SELECT, DESCRIBE), TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithWrongPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(SELECT, DESCRIBE), KEYSPACE, keyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnDifferentTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    String table2 = table + "_2";
    createTableAndInsertRecord(s, keyspace, table2);

    grantPermission(SELECT, TABLE, table2, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnDifferentKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    String keyspace2 = keyspace + "_2";

    s.execute("CREATE KEYSPACE " + keyspace2);
    grantPermission(SELECT, KEYSPACE, keyspace2, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnTableToDifferentRole() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(SELECT, TABLE, table, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnKeyspaceToDifferentRole() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(SELECT, KEYSPACE, keyspace, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("SELECT * from %s.%s", keyspace, table));
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantPermission(SELECT, TABLE, table, username);

    selectAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSelectStatementWithSelectPermissionOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantPermission(SELECT, KEYSPACE, keyspace, username);

    selectAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSelectStatementWithAllPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantAllPermission(TABLE, table, username);
    selectAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSelectStatementWithAllPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantAllPermission(KEYSPACE, keyspace, username);
    selectAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanSelectFromTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    selectAndVerify(s2, keyspace, table);
  }

  /*
   * INSERT statements tests.
   */

  @Test
  public void testInsertStatementWithNoPermissions() throws Exception {
    createTableAndVerify(s, keyspace, table);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithWrongPermissionsOnTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(MODIFY, DESCRIBE), TABLE, table, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithWrongPermissionsOnKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(MODIFY, DESCRIBE), KEYSPACE, keyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnDifferentTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    String table2 = table + "_2";
    createTableAndVerify(s, keyspace, table2);

    grantPermission(MODIFY, TABLE, table2, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnDifferentKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);

    String keyspace2 = keyspace + "_2";
    s.execute("CREATE KEYSPACE " + keyspace2);
    grantPermission(MODIFY, KEYSPACE, keyspace2, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnTableToDifferentRole() throws Exception {
    createTableAndVerify(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, TABLE, table, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnKeyspaceToDifferentRole() throws Exception {
    createTableAndVerify(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, KEYSPACE, keyspace, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("INSERT INTO %s.%s (h) VALUES (%d)", keyspace, table, VALUE));
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnTable() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(MODIFY, TABLE, table, username);
    insertRow(s2, keyspace, table);
  }

  @Test
  public void testInsertStatementWithModifyPermissionOnKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(MODIFY, KEYSPACE, keyspace, username);
    insertRow(s2, keyspace, table);
  }

  @Test
  public void testInsertStatementWithAllPermissionsOnTable() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantAllPermission(TABLE, table, username);
    insertRow(s2, keyspace, table);
  }

  @Test
  public void testInsertStatementWithAllPermissionsOnKeyspace() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantAllPermission(KEYSPACE, keyspace, username);
    insertRow(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanInsertIntoTable() throws Exception {
    createTableAndVerify(s, keyspace, table);

    // Make the role a superuser.
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    insertRow(s2, keyspace, table);
  }

  /*
   * UPDDATE statements tests.
   */

  @Test
  public void testUpdateStatementWithNoPermissions() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithWrongPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(DESCRIBE, MODIFY), TABLE, table, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithWrongPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(DESCRIBE, MODIFY), KEYSPACE, keyspace, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnDifferentTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    createTableAndVerify(s, keyspace, anotherTable);
    grantPermission(MODIFY, TABLE, anotherTable, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnDifferentKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    createKeyspaceAndVerify(s, anotherKeyspace);
    grantPermission(MODIFY, KEYSPACE, anotherKeyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnTableToDifferentRole() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, TABLE, table, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnKeyspaceToDifferentRole() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, KEYSPACE, keyspace, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("UPDATE %s.%s SET h = %d WHERE h = %d",
        keyspace, table, VALUE + 1, VALUE));
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantPermission(MODIFY, TABLE, table, username);
    updateRowAndVerify(s2, keyspace, table);
  }

  @Test
  public void testUpdateStatementWithModifyPermissionOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantPermission(MODIFY, KEYSPACE, keyspace, username);
    updateRowAndVerify(s2, keyspace, table);
  }

  @Test
  public void testUpdateStatementWithAllPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermission(TABLE, table, username);
    updateRowAndVerify(s2, keyspace, table);
  }

  @Test
  public void testUpdateStatementWithAllPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermission(KEYSPACE, keyspace, username);
    updateRowAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanUpdateTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);

    updateRowAndVerify(s2, keyspace, table);
  }

   /*
   * TRUNCATE statements tests.
   */

  @Test
  public void testTruncateStatementWithNoPermissions() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithWrongPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(DESCRIBE, MODIFY), TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithWrongPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantAllPermissionsExcept(Arrays.asList(DESCRIBE, MODIFY), KEYSPACE, keyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithModifyPermissionOnDifferentTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    createTableAndInsertRecord(s, keyspace, anotherTable);
    grantPermission(MODIFY, TABLE, anotherTable, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithModifyPermissionOnDifferentKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    createKeyspaceAndVerify(s, anotherKeyspace);
    grantPermission(MODIFY, KEYSPACE, anotherKeyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStamentWithModifyPermissionOnTableToDifferentRole() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, TABLE, table, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithModifyPermissionOnKeyspaceToDifferentRole()
      throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    testCreateRoleHelperWithSession(anotherUsername, password, true, false, false, s);
    grantPermission(MODIFY, KEYSPACE, keyspace, anotherUsername);

    thrown.expect(UnauthorizedException.class);
    s2.execute(String.format("TRUNCATE %s.%s", keyspace, table));
  }

  @Test
  public void testTruncateStatementWithModifyPermissionOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantPermission(MODIFY, TABLE, table, username);
    truncateTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testTruncateStatementWithModifyPermissionOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantPermission(MODIFY, KEYSPACE, keyspace, username);
    truncateTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testTruncateStatementWithAllPermissionsOnTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantAllPermission(TABLE, table, username);
    truncateTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testTruncateStatementWithAllPermissionsOnKeyspace() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    grantAllPermission(KEYSPACE, keyspace, username);
    truncateTableAndVerify(s2, keyspace, table);
  }

  @Test
  public void testSuperuserCanTruncateTable() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);
    s.execute(String.format("ALTER ROLE %s with SUPERUSER = true", username));
    Thread.sleep(TIME_SLEEP_MS);
    truncateTableAndVerify(s2, keyspace, table);
  }

  /*
   * Grant or Revoke test helper methods.
   */

  private void testGrantRevokeRoleWithoutPermissions(String stmtType) throws Exception {
    String r = String.format("%s_role_no_permissions", stmtType);
    testCreateRoleHelperWithSession(r, password, false, false, false, s);

    thrown.expect(UnauthorizedException.class);
    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", r, anotherUsername));
    } else {
      s2.execute(String.format("REVOKE %s FROM %s", r, anotherUsername));
    }
  }

  private void testGrantRevokeRoleWithoutPermissionOnRecipientRole(String stmtType)
      throws Exception {
    String granted_role = String.format("%s_role_without_permissions_on_recipient", stmtType);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    // Grant AUTHORIZE on granted_role.
    grantPermission(AUTHORIZE, ROLE, granted_role, username);

    thrown.expect(UnauthorizedException.class);
    thrown.expect(UnauthorizedException.class);
    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", granted_role, anotherUsername));
    } else {
      s2.execute(String.format("REVOKE %s FROM %s", granted_role, anotherUsername));
    }
  }

  private void testGrantRevokeRoleWithoutPermissionOnGrantedRole(String stmtType) throws Exception {
    String recipient_role = String.format("%s_without_permissions_on_granted", stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);

    // Grant AUTHORIZE on recipient_role */
    grantPermission(AUTHORIZE, ROLE, recipient_role, username);

    thrown.expect(UnauthorizedException.class);
    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", anotherUsername, recipient_role));
    } else {
      s2.execute(String.format("REVOKE %s FROM %s", anotherUsername, recipient_role));
    }
  }

  private void testGrantRevokeRoleWithWrongPermissionsOnGrantedAndRecipientRoles(String stmtType)
      throws Exception {
    String recipient_role = String.format("%s_recipient_role_wrong_permissions", stmtType);
    String granted_role = String.format("%s_granted_role_wrong_permissions", stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE), ROLE, granted_role, username);
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE), ROLE, recipient_role, username);

    thrown.expect(UnauthorizedException.class);
    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
    } else {
      s2.execute(String.format("revoke %s FROM %s", granted_role, recipient_role));
    }
  }

  private void testGrantRevokeRoleWithWrongPermissionsOnAllRoles(String stmtType) throws Exception {
    String recipient_role = String.format("%s_recipient_role_wrong_permissions_on_roles", stmtType);
    String granted_role = String.format("%s_granted_role_wrong_permissions_on_roles", stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE), ALL_ROLES, "", username);

    thrown.expect(UnauthorizedException.class);
    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
    } else {
      s2.execute(String.format("REVOKE %s FROM %s", granted_role, recipient_role));
    }
  }

  private void testGrantRevokeRoleWithPermissionOnGrantedAndRecipientRoles(String stmtType)
      throws Exception {
    String recipient_role = String.format("%s_recipient_role_full_permissions", stmtType);
    String granted_role = String.format("%s_granted_role_full_permissions", stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    grantPermissionNoSleep(AUTHORIZE, ROLE, granted_role, username);
    grantPermission(AUTHORIZE, ROLE, recipient_role, username);

    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
    } else {
      // Grant the role first using cassandra role.
      s.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
      s2.execute(String.format("REVOKE %s FROM %s", granted_role, recipient_role));
    }
  }

  private void testGrantRevokeRoleWithPermissionOnAllRoles(String stmtType) throws Exception {
    String recipient_role = String.format("%s_recipient_role_full_permissions_on_roles", stmtType);
    String granted_role = String.format("%s_granted_role_full_permissions_on_roles", stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    grantPermissionOnAllRoles(AUTHORIZE, username);

    if (stmtType.equals(GRANT)) {
      s2.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
    } else {
      // Grant the role first using cassandra role.
      s.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));
      s2.execute(String.format("REVOKE %s FROM %s", granted_role, recipient_role));
    }
  }

  //
  // GRANT ROLE statements
  //

  @Test
  public void testGrantRoleWithoutPermissions() throws Exception {
    testGrantRevokeRoleWithoutPermissions(GRANT);
  }

  // AUTHORIZE permission only on the granted role.
  @Test
  public void testGrantRoleWithoutPermissionOnRecipientRole() throws Exception {
    testGrantRevokeRoleWithoutPermissionOnRecipientRole(GRANT);
  }

  // AUTHORIZE permission only on the recipient role.
  @Test
  public void testGrantRoleWithoutPermissionOnGrantedRole() throws Exception {
    testGrantRevokeRoleWithoutPermissionOnGrantedRole(GRANT);
  }

  @Test
  public void testGrantRoleWithWrongPermissionsOnGrantedAndRecipientRoles() throws Exception {
    testGrantRevokeRoleWithWrongPermissionsOnGrantedAndRecipientRoles(GRANT);
  }

  @Test
  public void testGrantRoleWithWrongPermissionsOnAllRoles() throws Exception {
    testGrantRevokeRoleWithWrongPermissionsOnAllRoles(GRANT);
  }

  // AUTHORIZE permission only on the recipient and granted roles.
  @Test
  public void testGrantRoleWithPermissionOnGrantedAndRecipientRoles() throws Exception {
    testGrantRevokeRoleWithPermissionOnGrantedAndRecipientRoles(GRANT);
  }

  // AUTHORIZE permission only on ALL ROLES.
  @Test
  public void testGrantRoleWithPermissionOnALLRoles() throws Exception {
    testGrantRevokeRoleWithPermissionOnAllRoles(GRANT);
  }

  //
  // REVOKE ROLE statements
  //

  @Test
  public void testRevokeRoleWithoutPermissions() throws Exception {
    testGrantRevokeRoleWithoutPermissions(REVOKE);
  }

  // AUTHORIZE permission only on the granted role.
  @Test
  public void testRevokeRoleWithoutPermissionOnRecipientRole() throws Exception {
    Thread.sleep(40000);
    testGrantRevokeRoleWithoutPermissionOnRecipientRole(REVOKE);
  }

  // AUTHORIZE permission only on the recipient role.
  @Test
  public void testRevokeRoleWithoutPermissionOnRevokeedRole() throws Exception {
    testGrantRevokeRoleWithoutPermissionOnGrantedRole(REVOKE);
  }

  @Test
  public void testRevokeRoleWithWrongPermissionsOnGrantedAndRecipientRoles() throws Exception {
    testGrantRevokeRoleWithWrongPermissionsOnGrantedAndRecipientRoles(REVOKE);
  }

  @Test
  public void testRevokeRoleWithWrongPermissionsOnAllRoles() throws Exception {
    testGrantRevokeRoleWithWrongPermissionsOnAllRoles(REVOKE);
  }

  // AUTHORIZE permission only on the recipient and granted roles.
  @Test
  public void testRevokeRoleWithPermissionOnGrantedAndRecipientRoles() throws Exception {
    testGrantRevokeRoleWithPermissionOnGrantedAndRecipientRoles(REVOKE);
  }

  // AUTHORIZE permission only on ALL ROLES.
  @Test
  public void testRevokeRoleWithPermissionOnALLRoles() throws Exception {
    testGrantRevokeRoleWithPermissionOnAllRoles(REVOKE);
  }

  //
  // Grant/Revoke permissions on keyspaces/tables helper methods.
  //
  private String getGrantOnKeyspaceStmt() {
    return String.format("GRANT CREATE ON KEYSPACE %s TO %s", keyspace, anotherUsername);
  }

  private String getRevokeFromKeyspaceStmt() {
    return String.format("REVOKE CREATE ON KEYSPACE %s FROM %s", keyspace, anotherUsername);
  }

  private void grantAuthorizePermissionOnKeyspace() throws Exception {
    s.execute(getGrantOnKeyspaceStmt());
  }

  private void testGrantAuthorizePermissionOnKeyspaceFails() throws Exception {
    thrown.expect(UnauthorizedException.class);
    s2.execute(getGrantOnKeyspaceStmt());
  }

  private void testRevokeAuthorizePermissionFromKeyspaceFails() throws Exception {
    // First grant the permission using cassandra role.
    grantAuthorizePermissionOnKeyspace();
    thrown.expect(UnauthorizedException.class);
    s2.execute(getRevokeFromKeyspaceStmt());
  }

  private void testGrantRevokePermissionOnKeyspaceWithNoPermissions(String stmtType)
      throws Exception {
    if (stmtType.equals(GRANT)) {
      testGrantAuthorizePermissionOnKeyspaceFails();
    } else {
      testRevokeAuthorizePermissionFromKeyspaceFails();
    }
  }

  private void testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnKeyspace(String stmtType)
      throws Exception {
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE, DESCRIBE), KEYSPACE, keyspace, username);
    if (stmtType.equals(GRANT)) {
      testGrantAuthorizePermissionOnKeyspaceFails();
    } else {
      testRevokeAuthorizePermissionFromKeyspaceFails();
    }
  }

  private void testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnAllKeyspaces(
      String stmtType) throws Exception {
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE, DESCRIBE), ALL_KEYSPACES, "", username);
    if (stmtType.equals(GRANT)) {
      testGrantAuthorizePermissionOnKeyspaceFails();
    } else {
      testRevokeAuthorizePermissionFromKeyspaceFails();
    }
  }

  private void testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnKeyspace(String stmtType)
      throws Exception {
    grantPermission(AUTHORIZE, KEYSPACE, keyspace, username);
    if (stmtType.equals(GRANT)) {
      s2.execute(getGrantOnKeyspaceStmt());
    } else {
      s.execute(getGrantOnKeyspaceStmt());
      s2.execute(getRevokeFromKeyspaceStmt());
    }
  }

  private void testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnAllKeyspaces(
      String stmtType) throws Exception {
    grantPermissionOnAllKeyspaces(AUTHORIZE, username);
    if (stmtType.equals(GRANT)) {
      s2.execute(getGrantOnKeyspaceStmt());
    } else {
      s.execute(getGrantOnKeyspaceStmt());
      s2.execute(getRevokeFromKeyspaceStmt());
    }
  }

  private String getGrantOnTableStmt() {
    return String.format("GRANT CREATE ON TABLE %s.%s TO %s", keyspace, table, anotherUsername);
  }

  private String getRevokeFromTableStmt() {
    return String.format("REVOKE CREATE ON TABLE %s.%s FROM %s", keyspace, table,
        anotherUsername);
  }

  private void testGrantPermissionOnTableFails() throws Exception {
    thrown.expect(UnauthorizedException.class);
    s2.execute(getGrantOnTableStmt());
  }

  private void testRevokePermissionOnTableFails() throws Exception {
    // First grant the permission using cassandra role.
    s.execute(getGrantOnTableStmt());
    thrown.expect(UnauthorizedException.class);
    s2.execute(getRevokeFromTableStmt());
  }

  private void testGrantRevokePermissionOnTableWithNoPermissions(String stmtType) throws Exception {
    createTableAndVerify(s, keyspace, table);
    if (stmtType.equals(GRANT)) {
      testGrantPermissionOnTableFails();
    } else {
      testRevokePermissionOnTableFails();
    }
  }

  private void testGrantRevokePermissionOnTableWithWrongPermissionsOnTable(String stmtType)
      throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE, DESCRIBE), TABLE, table, username);
    if (stmtType.equals(GRANT)) {
      testGrantPermissionOnTableFails();
    } else {
      testRevokePermissionOnTableFails();
    }
  }

  private void testGrantRevokePermissionOnTableWithWrongPermissionsOnKeyspace(String stmtType)
      throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE, DESCRIBE), KEYSPACE, keyspace, username);
    if (stmtType.equals(GRANT)) {
      testGrantPermissionOnTableFails();
    } else {
      testRevokePermissionOnTableFails();
    }
  }

  private void testGrantRevokePermissionOnTableWithWrongPermissionsOnAllKeyspaces(String stmtType)
      throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantAllPermissionsExcept(Arrays.asList(AUTHORIZE, DESCRIBE), ALL_KEYSPACES, "", username);
    if (stmtType.equals(GRANT)) {
      testGrantPermissionOnTableFails();
    } else {
      testRevokePermissionOnTableFails();
    }
  }

  private void testGrantRevokePermissionOnTableWithAuthorizePermissionOnTable(String stmtType)
      throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(AUTHORIZE, TABLE, table, username);
    if (stmtType.equals(GRANT)) {
      s2.execute(getGrantOnTableStmt());
    } else {
      // First grant the permission using cassandra role.
      s.execute(getGrantOnTableStmt());
      s2.execute(getRevokeFromTableStmt());
    }
  }

  private void testGrantRevokePermissionOnTableWithAuthorizePermissionOnKeyspace(String stmtType)
      throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(AUTHORIZE, KEYSPACE, keyspace, username);
    if (stmtType.equals(GRANT)) {
      s2.execute(getGrantOnTableStmt());
    } else {
      // First grant the permission using cassandra role.
      s.execute(getGrantOnTableStmt());
      s2.execute(getRevokeFromTableStmt());
    }
  }

  private void testGrantRevokePermissionOnTableWithAuthorizePermissionOnAllKeyspaces(
      String stmtType) throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(AUTHORIZE, ALL_KEYSPACES, "", username);
    if (stmtType.equals(GRANT)) {
      s2.execute(getGrantOnTableStmt());
    } else {
      // First grant the permission using cassandra role.
      s.execute(getGrantOnTableStmt());
      s2.execute(getRevokeFromTableStmt());
    }
  }

  //
  // GRANT PERMISSION statements.
  //

  @Test
  public void testGrantPermissionOnKeyspaceWithNoPermissions() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithNoPermissions(GRANT);
  }

  @Test
  public void testGrantPermissionOnKeyspaceWithWrongPermissionsOnKeyspace() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnKeyspace(GRANT);
  }

  @Test
  public void testGrantPermissionOnKeyspaceWithWrongPermissionsOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnAllKeyspaces(GRANT);
  }

  @Test
  public void testGrantPermissionOnKeyspaceWithAuthorizePermissionOnKeyspace() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnKeyspace(GRANT);
  }

  @Test
  public void testGrantPermissionOnKeyspaceWithAuthorizePermissionOnAllKeyspaces()
      throws Exception {
    testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnAllKeyspaces(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithNoPermissions() throws Exception {
    testGrantRevokePermissionOnTableWithNoPermissions(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithWrongPermissionsOnTable() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnTable(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithWrongPermissionsOnKeyspace() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnKeyspace(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithWrongPermissionsOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnAllKeyspaces(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithAuthorizePermissionOnTable() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnTable(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithAuthorizePermissionOnKeyspace() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnKeyspace(GRANT);
  }

  @Test
  public void testGrantPermissionOnTableWithAuthorizePermissionOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnAllKeyspaces(GRANT);
  }

  //
  // REVOKE PERMISSION statements.
  //

  @Test
  public void testRevokePermissionOnKeyspaceWithNoPermissions() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithNoPermissions(REVOKE);
  }

  @Test
  public void testRevokePermissionOnKeyspaceWithWrongPermissionsOnKeyspace() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnKeyspace(REVOKE);
  }

  @Test
  public void testRevokePermissionOnKeyspaceWithWrongPermissionsOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithWrongPermissionsOnAllKeyspaces(REVOKE);
  }

  @Test
  public void testRevokePermissionOnKeyspaceWithAuthorizePermissionOnKeyspace() throws Exception {
    testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnKeyspace(REVOKE);
  }

  @Test
  public void testRevokePermissionOnKeyspaceWithAuthorizePermissionOnAllKeyspaces()
      throws Exception {
    testGrantRevokePermissionOnKeyspaceWithAuthorizePermissionOnAllKeyspaces(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithNoPermissions() throws Exception {
    testGrantRevokePermissionOnTableWithNoPermissions(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithWrongPermissionsOnTable() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnTable(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithWrongPermissionsOnKeyspace() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnKeyspace(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithWrongPermissionsOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnTableWithWrongPermissionsOnAllKeyspaces(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithAuthorizePermissionOnTable() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnTable(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithAuthorizePermissionOnKeyspace() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnKeyspace(REVOKE);
  }

  @Test
  public void testRevokePermissionOnTableWithAuthorizePermissionOnAllKeyspaces() throws Exception {
    testGrantRevokePermissionOnTableWithAuthorizePermissionOnAllKeyspaces(REVOKE);
  }

  @Test
  public void testPreparedCreateKeyspaceWithCreatePermission() throws Exception {
    grantPermissionOnAllKeyspaces(CREATE, username);

    // Prepare and execute statement.
    String create_keyspace_stmt = "CREATE KEYSPACE prepared_keyspace";
    PreparedStatement stmt = s2.prepare(create_keyspace_stmt);
    s2.execute(stmt.bind());

    revokePermission(CREATE, ALL_KEYSPACES, "", username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedCreateTableWithCreatePermission() throws Exception {
    grantPermission(CREATE, KEYSPACE, keyspace, username);

    s2.execute("USE " + keyspace);
    // Prepare and execute statement.
    String create_table_stmt = String.format("CREATE TABLE %s.%s (h int, v int, PRIMARY KEY(h))",
        keyspace, "prepared_table");
    PreparedStatement stmt = s2.prepare(create_table_stmt);
    s2.execute(stmt.bind());

    revokePermission(CREATE, KEYSPACE, keyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedAlterTableWithAlterPermission() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(ALTER, TABLE, table, username);

    // Prepare and execute statement.
    String alter_table_stmt = String.format("ALTER TABLE %s.%s ADD v2 int", keyspace, table);
    PreparedStatement stmt = s2.prepare(alter_table_stmt);
    s2.execute(stmt.bind());

    revokePermission(ALTER, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testTruncateTableWithModifyPermission() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(MODIFY, TABLE, table, username);

    // Prepare and excecute statement.
    String truncate_stmt = String.format("TRUNCATE %s.%s", keyspace, table);
    PreparedStatement stmt = s2.prepare(truncate_stmt);
    s2.execute(stmt.bind());

    revokePermission(MODIFY, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedInsertStmtWithSuperuserRole() throws Exception {
    testCreateRoleHelperWithSession(anotherUsername, password, true, true, false, s);
    Session s3 = getSession(anotherUsername, password);

    createTableAndVerify(s, keyspace, table);

    // Prepare and execute statement.
    String insert_stmt = String.format("INSERT INTO %s.%s (h, v) VALUES (?, ?)", keyspace, table);
    PreparedStatement stmt = s3.prepare(insert_stmt);

    ResultSet rs = s3.execute(stmt.bind(3, 5));

    s.execute(String.format("ALTER ROLE %s with SUPERUSER = false", anotherUsername));
    Thread.sleep(TIME_SLEEP_MS);

    thrown.expect(UnauthorizedException.class);
    rs = s3.execute(stmt.bind(4, 2));
  }

  @Test
  public void testPreparedInsertStmtWithModifyPermission() throws Exception {
    createTableAndVerify(s, keyspace, table);

    grantPermission(MODIFY, TABLE, table, username);

    // Prepare and execute statement.
    String insert_stmt = String.format("INSERT INTO %s.%s (h, v) VALUES (?, ?)", keyspace, table);
    PreparedStatement stmt = s2.prepare(insert_stmt);

    ResultSet rs = s2.execute(stmt.bind(3, 5));

    // Revoke the MODIFY permissions so the next execution of the prepared statement fails.
    revokePermission(MODIFY, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    rs = s2.execute(stmt.bind(4, 2));
  }

  @Test
  public void testPreparedSelectStmtWithSelectPermission() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantPermission(SELECT, TABLE, table, username);

    // Prepare and execute statement.
    String select_stmt = String.format("SELECT * FROM %s.%s", keyspace, table);
    PreparedStatement stmt = s2.prepare(select_stmt);

    ResultSet rs = s2.execute(stmt.bind());
    List<Row> rows = rs.all();
    assertEquals(1, rows.size());
    assertEquals(VALUE, rows.get(0).getInt("h"));
    assertEquals(VALUE, rows.get(0).getInt("v"));

    revokePermission(SELECT, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    rs = s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedUpdateStmtWithModifyPermission() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantPermission(MODIFY, TABLE, table, username);

    // Prepare and execute statement.
    String update_stmt = String.format("UPDATE %s.%s set v = 1 WHERE h = ?", keyspace, table);
    PreparedStatement stmt = s2.prepare(update_stmt);

    s2.execute(stmt.bind(VALUE));

    revokePermission(MODIFY, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind(VALUE));
  }

  @Test
  public void testPreparedDeleteStmtWithModifyPermission() throws Exception {
    createTableAndInsertRecord(s, keyspace, table);

    grantPermission(MODIFY, TABLE, table, username);

    // Prepare and execute statement.
    String delete_stmt = String.format("DELETE FROM %s.%s WHERE h = ?", keyspace, table);
    PreparedStatement stmt = s2.prepare(delete_stmt);
    s2.execute(stmt.bind(VALUE));

    revokePermission(MODIFY, TABLE, table, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind(VALUE));
  }

  private void testPreparedGrantRevokeRoleStatementWithAuthorizePermission(String stmtType)
      throws Exception {
    String recipient_role = String.format("%s_recipient_%s", username, stmtType);
    String granted_role = String.format("%s_granted_%s", username, stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    grantPermissionNoSleep(AUTHORIZE, ROLE, granted_role, username);
    grantPermission(AUTHORIZE, ROLE, recipient_role, username);

    String stmt;
    if (stmtType.equals(GRANT)) {
      stmt = String.format("GRANT %s TO %s", granted_role, recipient_role);
    } else {
      // Grant the role first using cassandra role.
      s.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));

      stmt = String.format("REVOKE %s FROM %s", granted_role, recipient_role);
    }
    PreparedStatement preparedStatement = s2.prepare(stmt);
    revokePermission(AUTHORIZE, ROLE, granted_role, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(preparedStatement.bind());
  }

  private void testPreparedGrantRevokeRoleStatementWithSuperuserRole(String stmtType)
      throws Exception {
    String recipient_role = String.format("recipient_%s_%s_test", username, stmtType);
    String granted_role = String.format("granted_%s_%s_test", username, stmtType);
    testCreateRoleHelperWithSession(recipient_role, password, false, false, false, s);
    testCreateRoleHelperWithSession(granted_role, password, false, false, false, s);

    testCreateRoleHelperWithSession(anotherUsername, password, true, true, false, s);
    Session s3 = getSession(anotherUsername, password);

    String stmt;
    if (stmtType.equals(GRANT)) {
      stmt = String.format("GRANT %s TO %s", granted_role, recipient_role);
    } else {
      // Grant the role first using cassandra role.
      s.execute(String.format("GRANT %s TO %s", granted_role, recipient_role));

      stmt = String.format("REVOKE %s FROM %s", granted_role, recipient_role);
    }
    PreparedStatement preparedStatement = s3.prepare(stmt);

    s.execute(String.format("ALTER ROLE %s with SUPERUSER = false", anotherUsername));
    Thread.sleep(TIME_SLEEP_MS);

    thrown.expect(UnauthorizedException.class);
    s3.execute(preparedStatement.bind());
  }

  @Test
  public void testPreparedGrantRoleStatementWithAuthorizePermission() throws Exception {
    testPreparedGrantRevokeRoleStatementWithAuthorizePermission(GRANT);
  }

  @Test
  public void testPreparedRevokeRoleStatementWithAuthorizePermission() throws Exception {
    testPreparedGrantRevokeRoleStatementWithAuthorizePermission(REVOKE);
  }

  @Test
  public void testPreparedGrantRoleStatementWithSuperuserRole() throws Exception {
    testPreparedGrantRevokeRoleStatementWithSuperuserRole(GRANT);
  }

  @Test
  public void testPreparedRevokeRoleStatementWithSuperuserRole() throws Exception {
    testPreparedGrantRevokeRoleStatementWithSuperuserRole(REVOKE);
  }

  @Test
  public void testPreparedGrantPermissionOnKeyspaceWithAuthorizePermission() throws Exception {
    grantPermission(AUTHORIZE, KEYSPACE, keyspace, username);

    String grant_permission_stmt = String.format("GRANT CREATE ON KEYSPACE %s to %s",
        keyspace, username);
    PreparedStatement stmt = s2.prepare(grant_permission_stmt);
    s2.execute(stmt.bind());

    revokePermission(AUTHORIZE, KEYSPACE, keyspace, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedGrantPermissionOnTableWithAuthorizePermission() throws Exception {
    createTableAndVerify(s, keyspace, table);
    grantPermission(AUTHORIZE, TABLE, table, username);

    String grant_permission_stmt = String.format("GRANT MODIFY ON TABLE %s.%s to %s",
        keyspace, table, username);
    PreparedStatement stmt = s2.prepare(grant_permission_stmt);
    s2.execute(stmt.bind());

    revokePermission(AUTHORIZE, TABLE, table, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedGrantPermissionOnRoleStmtWithAuthorizePermission() throws Exception {
    testCreateRoleHelperWithSession(anotherUsername, password, false, false, false, s);

    grantPermission(AUTHORIZE, ROLE, anotherUsername, username);

    String grant_permission_stmt = String.format("GRANT DROP ON ROLE %s to %s",
        anotherUsername, username);
    PreparedStatement stmt = s2.prepare(grant_permission_stmt);
    s2.execute(stmt.bind());

    revokePermission(AUTHORIZE, ROLE, anotherUsername, username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedGrantPermissionOnAllKeyspaesWithAuthorizePermission() throws Exception {
    grantPermissionOnAllKeyspaces(AUTHORIZE, username);

    String grant_permission_stmt = String.format("GRANT SELECT ON ALL KEYSPACES TO %s", username);
    PreparedStatement stmt = s2.prepare(grant_permission_stmt);
    s2.execute(stmt.bind());

    revokePermission(AUTHORIZE, ALL_KEYSPACES, "", username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedGrantPermissionOnAllRolesWithAuthorizePermission() throws Exception {
    grantPermissionOnAllRoles(AUTHORIZE, username);

    String grant_permission_stmt = String.format("GRANT DROP ON ALL ROLES TO %s", username);
    PreparedStatement stmt = s2.prepare(grant_permission_stmt);
    s2.execute(stmt.bind());

    revokePermission(AUTHORIZE, ALL_ROLES, "", username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedDropRoleStmtWithDropPermission() throws Exception {
    testCreateRoleHelperWithSession(anotherUsername, password, false, false, false, s);
    grantPermission(DROP, ROLE, anotherUsername, username);

    String drop_stmt = String.format("DROP ROLE %s", anotherUsername);
    PreparedStatement stmt = s2.prepare(drop_stmt);
    s2.execute(stmt.bind());

    // Create it again.
    testCreateRoleHelperWithSession(anotherUsername, password, false, false, false, s);
    revokePermission(DROP, ROLE, anotherUsername, username);
    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedDropKeyspaceStmtWithDropPermission() throws Exception {
    String newKeyspace = "prepared_keyspace";
    createKeyspaceAndVerify(s, newKeyspace);

    // Permission has to be granted on ALL KEYSPACES. Granting DROP permission on a specific
    // keyspace only authorizes the user to drop tables in that keyspace, but not to drop the
    // keyspace.
    grantPermissionOnAllKeyspaces(DROP, username);

    String drop_stmt = String.format("DROP KEYSPACE %s", newKeyspace);
    PreparedStatement stmt = s2.prepare(drop_stmt);
    s2.execute(stmt.bind());

    revokePermission(DROP, ALL_KEYSPACES, "", username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }

  @Test
  public void testPreparedDropTableStmtWithDropPermission() throws Exception {
    createTableAndVerify(s, keyspace, table);

    grantPermission(DROP, TABLE, String.format("%s.%s", keyspace, table), username);
    Thread.sleep(5000);

    String drop_stmt = String.format("DROP TABLE %s.%s", keyspace, table);
    PreparedStatement stmt = s2.prepare(drop_stmt);
    s2.execute(stmt.bind());

    createTableAndVerify(s, keyspace, table);
    revokePermission(DROP, TABLE, String.format("%s.%s", keyspace, table), username);

    thrown.expect(UnauthorizedException.class);
    s2.execute(stmt.bind());
  }
}
