package org.apache.lucene.index.codecs.sep;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.MultiLevelSkipListWriter;

// nocommit -- skip data should somehow be more local to the
// particular stream (doc, freq, pos, payload)

/**
 * Implements the skip list writer for the default posting list format
 * that stores positions and payloads.
 */
class SepSkipListWriter extends MultiLevelSkipListWriter {
  private int[] lastSkipDoc;
  private int[] lastSkipPayloadLength;
  private long[] lastSkipPayloadPointer;

  private IntIndexOutput.Index[] docIndex;
  private IntIndexOutput.Index[] freqIndex;
  private IntIndexOutput.Index[] posIndex;
  
  private IntIndexOutput freqOutput;
  private IntIndexOutput docOutput;
  // nocommit -- private again
  IntIndexOutput posOutput;
  // nocommit -- private again
  IndexOutput payloadOutput;

  private int curDoc;
  private boolean curStorePayloads;
  private int curPayloadLength;
  private long curPayloadPointer;
  
  SepSkipListWriter(int skipInterval, int numberOfSkipLevels, int docCount,
                    IntIndexOutput freqOutput,
                    IntIndexOutput docOutput,
                    IntIndexOutput posOutput,
                    IndexOutput payloadOutput)
    throws IOException {
    super(skipInterval, numberOfSkipLevels, docCount);

    this.freqOutput = freqOutput;
    this.docOutput = docOutput;
    this.posOutput = posOutput;
    this.payloadOutput = payloadOutput;
    
    lastSkipDoc = new int[numberOfSkipLevels];
    lastSkipPayloadLength = new int[numberOfSkipLevels];
    // nocommit -- also cutover normal IndexOutput to use getIndex()?
    lastSkipPayloadPointer = new long[numberOfSkipLevels];

    freqIndex = new IntIndexOutput.Index[numberOfSkipLevels];
    docIndex = new IntIndexOutput.Index[numberOfSkipLevels];
    posIndex = new IntIndexOutput.Index[numberOfSkipLevels];

    for(int i=0;i<numberOfSkipLevels;i++) {
      freqIndex[i] = freqOutput.index();
      if (Codec.DEBUG) {
        freqIndex[i].desc = "sslw.freq.level" + i;
      }
      docIndex[i] = docOutput.index();
      if (Codec.DEBUG) {
        docIndex[i].desc = "sslw.doc.level" + i;
      }
      if (posOutput != null) {
        posIndex[i] = posOutput.index();
        if (Codec.DEBUG) {
          posIndex[i].desc = "sslw.pos.level" + i;
        }
      }
    }
  }

  boolean omitTF;

  void setOmitTF(boolean v) {
    omitTF = v;
  }

  void setPosOutput(IntIndexOutput posOutput) throws IOException {
    this.posOutput = posOutput;
    for(int i=0;i<numberOfSkipLevels;i++) {
      posIndex[i] = posOutput.index();
      if (Codec.DEBUG) {
        posIndex[i].desc = "sslw.pos.level" + i;
      }
    }
  }

  void setPayloadOutput(IndexOutput payloadOutput) {
    this.payloadOutput = payloadOutput;
  }

  /**
   * Sets the values for the current skip data. 
   */
  // Called @ every index interval (every 128th (by default)
  // doc)
  void setSkipData(int doc, boolean storePayloads, int payloadLength) {
    this.curDoc = doc;
    this.curStorePayloads = storePayloads;
    this.curPayloadLength = payloadLength;
    if (payloadOutput != null) {
      this.curPayloadPointer = payloadOutput.getFilePointer();
    }
  }

  // Called @ start of new term
  protected void resetSkip(IntIndexOutput.Index topDocIndex, IntIndexOutput.Index topFreqIndex, IntIndexOutput.Index topPosIndex)
    throws IOException {
    super.resetSkip();
    if (Codec.DEBUG) {
      System.out.println("sslw.reset docIndexBase=" + topDocIndex +
                         " freqIndexBase=" + topFreqIndex +
                         " topPosIndex=" + (topPosIndex == null? "null" : (""+topPosIndex)));
    }

    Arrays.fill(lastSkipDoc, 0);
    Arrays.fill(lastSkipPayloadLength, -1);  // we don't have to write the first length in the skip list
    for(int i=0;i<numberOfSkipLevels;i++) {
      docIndex[i].set(topDocIndex);
      freqIndex[i].set(topFreqIndex);
      if (posOutput != null) {
        posIndex[i].set(topPosIndex);
      }
    }
    if (payloadOutput != null) {
      Arrays.fill(lastSkipPayloadPointer, payloadOutput.getFilePointer());
    }
  }
  
  @Override
  protected void writeSkipData(int level, IndexOutput skipBuffer) throws IOException {
    // To efficiently store payloads in the posting lists we do not store the length of
    // every payload. Instead we omit the length for a payload if the previous payload had
    // the same length.
    // However, in order to support skipping the payload length at every skip point must be known.
    // So we use the same length encoding that we use for the posting lists for the skip data as well:
    // Case 1: current field does not store payloads
    //           SkipDatum                 --> DocSkip, FreqSkip, ProxSkip
    //           DocSkip,FreqSkip,ProxSkip --> VInt
    //           DocSkip records the document number before every SkipInterval th  document in TermFreqs. 
    //           Document numbers are represented as differences from the previous value in the sequence.
    // Case 2: current field stores payloads
    //           SkipDatum                 --> DocSkip, PayloadLength?, FreqSkip,ProxSkip
    //           DocSkip,FreqSkip,ProxSkip --> VInt
    //           PayloadLength             --> VInt    
    //         In this case DocSkip/2 is the difference between
    //         the current and the previous value. If DocSkip
    //         is odd, then a PayloadLength encoded as VInt follows,
    //         if DocSkip is even, then it is assumed that the
    //         current payload length equals the length at the previous
    //         skip point
    if (Codec.DEBUG) {
      System.out.println("ssw level=" + level + " curDoc=" + curDoc + " lastDoc=" + lastSkipDoc[level] + " delta=" + (curDoc - lastSkipDoc[level]) + " storePayloads=" + curStorePayloads + " skipBufferFP=" + skipBuffer.getFilePointer() + " curPayloadLen=" + curPayloadLength + " freqIndex=" + freqOutput.descFilePointer() + " docIndex=" + docOutput.descFilePointer() + " posIndex=" + posOutput.descFilePointer() + " curPayloadPointer=" + curPayloadPointer);
    }

    assert !omitTF || !curStorePayloads;

    if (curStorePayloads) {
      int delta = curDoc - lastSkipDoc[level];
      if (curPayloadLength == lastSkipPayloadLength[level]) {
        // the current payload length equals the length at the previous skip point,
        // so we don't store the length again
        skipBuffer.writeVInt(delta << 1);
      } else {
        // the payload length is different from the previous one. We shift the DocSkip, 
        // set the lowest bit and store the current payload length as VInt.
        skipBuffer.writeVInt(delta << 1 | 1);
        skipBuffer.writeVInt(curPayloadLength);
        lastSkipPayloadLength[level] = curPayloadLength;
      }
    } else {
      // current field does not store payloads
      skipBuffer.writeVInt(curDoc - lastSkipDoc[level]);
    }

    if (!omitTF) {
      freqIndex[level].mark();
      freqIndex[level].write(skipBuffer, false);
    }
    docIndex[level].mark();
    docIndex[level].write(skipBuffer, false);
    if (!omitTF) {
      posIndex[level].mark();
      posIndex[level].write(skipBuffer, false);
      skipBuffer.writeVInt((int) (curPayloadPointer - lastSkipPayloadPointer[level]));
    }

    lastSkipDoc[level] = curDoc;
    lastSkipPayloadPointer[level] = curPayloadPointer;
  }
}
