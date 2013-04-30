/*
 * Copyright (C) 2013  Ohm Data
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  This file incorporates work covered by the following copyright and
 *  permission notice:
 */

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package ohmdb.io.hfile;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.FSDataInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * BoundedRangeFIleInputStream abstracts a contiguous region of a Hadoop
 * FSDataInputStream as a regular input stream. One can create multiple
 * BoundedRangeFileInputStream on top of the same FSDataInputStream and they
 * would not interfere with each other.
 * Copied from hadoop-335 tfile.
 */
@InterfaceAudience.Private
class BoundedRangeFileInputStream  extends InputStream {

  private FSDataInputStream in;
  private long pos;
  private long end;
  private long mark;
  private final byte[] oneByte = new byte[1];
  private final boolean pread;

  /**
   * Constructor
   *
   * @param in
   *          The FSDataInputStream we connect to.
   * @param offset
   *          Beginning offset of the region.
   * @param length
   *          Length of the region.
   * @param pread If true, use Filesystem positional read rather than seek+read.
   *
   *          The actual length of the region may be smaller if (off_begin +
   *          length) goes beyond the end of FS input stream.
   */
  public BoundedRangeFileInputStream(FSDataInputStream in, long offset,
      long length, final boolean pread) {
    if (offset < 0 || length < 0) {
      throw new IndexOutOfBoundsException("Invalid offset/length: " + offset
          + "/" + length);
    }

    this.in = in;
    this.pos = offset;
    this.end = offset + length;
    this.mark = -1;
    this.pread = pread;
  }

  @Override
  public int available() throws IOException {
    int avail = in.available();
    if (pos + avail > end) {
      avail = (int) (end - pos);
    }

    return avail;
  }

  @Override
  public int read() throws IOException {
    int ret = read(oneByte);
    if (ret == 1) return oneByte[0] & 0xff;
    return -1;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
      throw new IndexOutOfBoundsException();
    }

    int n = (int) Math.min(Integer.MAX_VALUE, Math.min(len, (end - pos)));
    if (n == 0) return -1;
    int ret = 0;
    if (this.pread) {
      ret = in.read(pos, b, off, n);
    } else {
      synchronized (in) {
        in.seek(pos);
        ret = in.read(b, off, n);
      }
    }
    if (ret < 0) {
      end = pos;
      return -1;
    }
    pos += ret;
    return ret;
  }

  @Override
  /*
   * We may skip beyond the end of the file.
   */
  public long skip(long n) throws IOException {
    long len = Math.min(n, end - pos);
    pos += len;
    return len;
  }

  @Override
  public void mark(int readlimit) {
    mark = pos;
  }

  @Override
  public void reset() throws IOException {
    if (mark < 0) throw new IOException("Resetting to invalid mark");
    pos = mark;
  }

  @Override
  public boolean markSupported() {
    return true;
  }

  @Override
  public void close() {
    // Invalidate the state of the stream.
    in = null;
    pos = end;
    mark = -1;
  }
}