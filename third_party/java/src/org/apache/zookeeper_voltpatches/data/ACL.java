// File generated by hadoop record compiler. Do not edit.
/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.zookeeper_voltpatches.data;

import java.util.*;
import org.apache.jute_voltpatches.*;
import org.apache.zookeeper_voltpatches.data.ACL;
public class ACL implements Record {
  private int perms;
  private org.apache.zookeeper_voltpatches.data.Id id;
  public ACL() {
  }
  public ACL(
        int perms,
        org.apache.zookeeper_voltpatches.data.Id id) {
    this.perms=perms;
    this.id=id;
  }
  public int getPerms() {
    return perms;
  }
  public void setPerms(int m_) {
    perms=m_;
  }
  public org.apache.zookeeper_voltpatches.data.Id getId() {
    return id;
  }
  public void setId(org.apache.zookeeper_voltpatches.data.Id m_) {
    id=m_;
  }
  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(this,tag);
    a_.writeInt(perms,"perms");
    a_.writeRecord(id,"id");
    a_.endRecord(this,tag);
  }
  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(tag);
    perms=a_.readInt("perms");
    id= new org.apache.zookeeper_voltpatches.data.Id();
    a_.readRecord(id,"id");
    a_.endRecord(tag);
}
  @Override
public String toString() {
    try {
      java.io.ByteArrayOutputStream s =
        new java.io.ByteArrayOutputStream();
      CsvOutputArchive a_ =
        new CsvOutputArchive(s);
      a_.startRecord(this,"");
    a_.writeInt(perms,"perms");
    a_.writeRecord(id,"id");
      a_.endRecord(this,"");
      return new String(s.toByteArray(), "UTF-8");
    } catch (Throwable ex) {
      ex.printStackTrace();
    }
    return "ERROR";
  }
  public void write(java.io.DataOutput out) throws java.io.IOException {
    BinaryOutputArchive archive = new BinaryOutputArchive(out);
    serialize(archive, "");
  }
  public void readFields(java.io.DataInput in) throws java.io.IOException {
    BinaryInputArchive archive = new BinaryInputArchive(in);
    deserialize(archive, "");
  }
  public int compareTo (Object peer_) throws ClassCastException {
    if (!(peer_ instanceof ACL)) {
      throw new ClassCastException("Comparing different types of records.");
    }
    ACL peer = (ACL) peer_;
    int ret = 0;
    ret = (perms == peer.perms)? 0 :((perms<peer.perms)?-1:1);
    if (ret != 0) return ret;
    ret = id.compareTo(peer.id);
    if (ret != 0) return ret;
     return ret;
  }
  @Override
public boolean equals(Object peer_) {
    if (!(peer_ instanceof ACL)) {
      return false;
    }
    if (peer_ == this) {
      return true;
    }
    ACL peer = (ACL) peer_;
    boolean ret = false;
    ret = (perms==peer.perms);
    if (!ret) return ret;
    ret = id.equals(peer.id);
    if (!ret) return ret;
     return ret;
  }
  @Override
public int hashCode() {
    int result = 17;
    int ret;
    ret = perms;
    result = 37*result + ret;
    ret = id.hashCode();
    result = 37*result + ret;
    return result;
  }
  public static String signature() {
    return "LACL(iLId(ss))";
  }
}
