/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connector.selectdb.serialize;

import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SelectDBCsvSerializer extends SelectDBBaseSerializer implements SelectDBSerializer {
    private static final long serialVersionUID = 1L;

    private final String columnSeparator;
    private final SeaTunnelRowType seaTunnelRowType;

    public SelectDBCsvSerializer(String sp, SeaTunnelRowType seaTunnelRowType) {
        this.seaTunnelRowType = seaTunnelRowType;
        this.columnSeparator = SelectDBDelimiterParser.parse(sp, "\t");
    }

    @Override
    public void open() throws IOException {

    }

    @Override
    public byte[] serialize(SeaTunnelRow row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.getFields().length; i++) {
            String value = convert(seaTunnelRowType.getFieldType(i), row.getField(i));
            sb.append(null == value ? "\\N" : value);
            if (i < row.getFields().length - 1) {
                sb.append(columnSeparator);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {

    }

}
