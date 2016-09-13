/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.schemabuilder.Create;
import com.datastax.driver.core.schemabuilder.SchemaBuilder;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static com.datastax.driver.core.TestUtils.ipOfNode;
import static com.datastax.driver.core.TestUtils.waitForUp;
import static com.datastax.driver.core.querybuilder.QueryBuilder.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

@CCMConfig(numberOfNodes = 2)
public class FrameLengthTest extends CCMTestsSupport {

    Logger logger = LoggerFactory.getLogger(FrameLengthTest.class);

    private static final String tableName = "blob_table";
    private static final int colCount = 256;
    private static final int rowsPerPartitionCount = 4;
    private static final int partitionCount = 1;
    private static final int bytesPerCol = 1024;

    @BeforeClass(groups = {"isolated"})
    public void beforeTestClass() throws Exception {
        // Set max frame size to 1MB to make it easier to manifest frame length error.
        System.setProperty("com.datastax.driver.NATIVE_TRANSPORT_MAX_FRAME_SIZE_IN_MB", "1");
        super.beforeTestClass();
    }

    @Override
    public void onTestContextInitialized() {
        logger.info("Creating table {} with {} {}-byte blob columns", tableName, colCount, bytesPerCol);
        Random random = new Random();
        // Create table
        Create create = SchemaBuilder.createTable(tableName).addPartitionKey("k", DataType.cint()).addClusteringColumn("c", DataType.cint());
        for (int i = 0; i < colCount; i++) {
            create.addColumn("col" + i, DataType.blob());
        }
        execute(create.getQueryString());

        // build prepared statement.
        Insert insert = insertInto(tableName).value("k", bindMarker()).value("c", bindMarker());
        for (int i = 0; i < colCount; i++) {
            insert = insert.value("col" + i, bindMarker());
        }

        PreparedStatement prepared = session().prepare(insert);

        // Insert rows.
        logger.info("Inserting data for {} partitions.", partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            logger.info("Inserting {} rows in partition {}", rowsPerPartitionCount, i);
            for (int r = 0; r < rowsPerPartitionCount; r++) {
                BoundStatement stmt = prepared.bind();
                stmt.setInt("k", i);
                stmt.setInt("c", r);
                for (int c = 0; c < colCount; c++) {
                    byte[] b = new byte[bytesPerCol];
                    random.nextBytes(b);
                    ByteBuffer in = ByteBuffer.wrap(b);
                    stmt.setBytes("col" + c, in);
                }
                session().execute(stmt);
            }
        }
        logger.info("Done loading {}", tableName);
    }

    /**
     * Validates that if a frame is received that exceeds NATIVE_TRANSPORT_MAX_FRAME_SIZE_IN_MB that
     * the driver is able to recover and reconnect to each host and make subsequent queries.  It
     * configures NATIVE_TRANSPORT_MAX_FRAME_SIZE_IN_MB to 1 MB to make the error easier to reproduce.
     *
     * @jira_ticket JAVA-1292
     * @jira_ticket JAVA-1293
     * @test_category connection
     */
    @Test(groups = "isolated")
    public void should_throw_exception_when_frame_exceeds_configured_max() {
        // Capture the connection logger to check for TooLongFrameException.
        org.apache.log4j.Logger connectionLogger = org.apache.log4j.Logger.getLogger(Connection.class);
        Level originalLevel = connectionLogger.getLevel();
        connectionLogger.setLevel(Level.DEBUG);
        MemoryAppender logs = new MemoryAppender();
        connectionLogger.addAppender(logs);
        try {
            session().execute(select().from(tableName).where(eq("k", 0)));
            fail("Exception expected");
        } catch (NoHostAvailableException e) {
            // Both hosts should have been tried.
            assertThat(e.getErrors()).hasSize(2);
        } finally {
            // Check that TooLongFrameException is encountered as it isn't raised to the NoHostAvailableException.
            assertThat(logs.get()).contains("io.netty.handler.codec.TooLongFrameException");
            connectionLogger.setLevel(originalLevel);
            connectionLogger.removeAppender(logs);
        }
        // Connection will be lost for both hosts, but they should be able to reconnect.
        waitForUp(ipOfNode(1), cluster(), 10);
        waitForUp(ipOfNode(2), cluster(), 10);

        // Should be able to make a query that is less than the max frame size.
        ResultSet result = session().execute(select().from(tableName).where(eq("k", 0)).and(eq("c", 0)));
        assertThat(result.getAvailableWithoutFetching()).isEqualTo(1);
    }
}