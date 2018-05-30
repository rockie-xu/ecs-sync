/*
 * Copyright 2013-2018 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.ecs.sync.rest;

import com.emc.ecs.sync.util.ReadOnlyIterator;
import com.emc.util.StreamUtil;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class CsvWriterTest {
    private static final String FAIL_MESSAGE = "failure message";

    @Test
    public void testWriterFailure() throws Exception {
        // create a writer that will fail immediately
        AbstractCsvWriter csvWriter = new MyCsvWriter(failingList);
        Thread thread = new Thread(csvWriter);
        thread.setDaemon(true); // make sure thread dies when our test is done
        thread.start();

        // read from it
        long start = System.currentTimeMillis();
        byte[] bytes = StreamUtil.readAsBytes(csvWriter.getReadStream());
        Assert.assertEquals(22, bytes.length);
        Assert.assertNotNull(csvWriter.getError());
        Assert.assertEquals(FAIL_MESSAGE, csvWriter.getError().getMessage());

        // make sure it fails within a couple seconds
        Assert.assertTrue(System.currentTimeMillis() - start < 2000);
    }

    class MyRecord {
        public MyRecord(String col1, String col2) {
            this.col1 = col1;
            this.col2 = col2;
        }

        public String col1;
        public String col2;
    }

    Iterable<MyRecord> failingList = () -> new ReadOnlyIterator<MyRecord>() {
        @Override
        protected MyRecord getNextObject() {
            throw new RuntimeException(FAIL_MESSAGE);
        }
    };

    class MyCsvWriter extends AbstractCsvWriter<MyRecord> {
        MyCsvWriter(Iterable<MyRecord> records) throws IOException {
            super(records);
        }

        @Override
        protected String[] getHeaders() {
            return new String[]{"column 1", "column 2"};
        }

        @Override
        protected Object[] getColumns(MyRecord record) {
            return new Object[]{record.col1, record.col2};
        }
    }
}
