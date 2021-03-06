/**
 * Copyright 2019 The JoyQueue Authors.
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
package org.joyqueue.store;

import java.nio.ByteBuffer;

/**
 * @author liyue25
 * Date: 2018/10/18
 */
public class WriteRequest{
    private final short partition;
    private ByteBuffer buffer;
    private int batchSize;


    public WriteRequest(short partition, ByteBuffer buffer) {
        this.partition = partition;
        this.buffer = buffer;
    }

    public WriteRequest(short partition, ByteBuffer buffer, int batchSize) {
        this.partition = partition;
        this.buffer = buffer;
        this.batchSize = batchSize;
    }

    public short getPartition() {
        return partition;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
    }
}
