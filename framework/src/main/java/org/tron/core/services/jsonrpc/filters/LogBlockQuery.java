package org.tron.core.services.jsonrpc.filters;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.tron.common.crypto.Hash;
import org.tron.common.logsfilter.Bloom;
import org.tron.core.exception.JsonRpcInvalidParamsException;
import org.tron.core.exception.JsonRpcTooManyResultException;
import org.tron.core.store.SectionBloomStore;

/**
 * query possible block list by logFilterWrapper
 * warning: must not use bitSet.set(0, bitSet.length()) !
 */
@Slf4j(topic = "API")
public class LogBlockQuery {

  public static final int MAX_RESULT = 10000;
  private LogFilterWrapper logFilterWrapper;
  private SectionBloomStore sectionBloomStore;
  private ExecutorService sectionExecutor;

  private int minSection;
  private int maxSection;
  private long minBlock;
  private long maxBlock;
  private long currentMaxBlockNum;

  public LogBlockQuery(LogFilterWrapper logFilterWrapper, SectionBloomStore sectionBloomStore,
      long currentMaxBlockNum, ExecutorService executor) {
    this.logFilterWrapper = logFilterWrapper;
    this.sectionBloomStore = sectionBloomStore;
    this.sectionExecutor = executor;
    this.currentMaxBlockNum = currentMaxBlockNum;

    if (logFilterWrapper.getFromBlock() == Long.MAX_VALUE) {
      minSection = (int) (currentMaxBlockNum / Bloom.BLOOM_BIT_SIZE);
      minBlock = currentMaxBlockNum;
    } else {
      minSection = (int) (logFilterWrapper.getFromBlock() / Bloom.BLOOM_BIT_SIZE);
      minBlock = logFilterWrapper.getFromBlock();
    }

    if (logFilterWrapper.getToBlock() == Long.MAX_VALUE) {
      maxSection = (int) (currentMaxBlockNum / Bloom.BLOOM_BIT_SIZE);
      maxBlock = currentMaxBlockNum;
    } else {
      maxSection = (int) (logFilterWrapper.getToBlock() / Bloom.BLOOM_BIT_SIZE);
      maxBlock = logFilterWrapper.getToBlock();
    }
  }

  public List<Long> getPossibleBlock() throws ExecutionException, InterruptedException,
      JsonRpcTooManyResultException {
    List<Long> blockNumList = new ArrayList<>();
    if(minBlock > currentMaxBlockNum){
      return blockNumList;
    }

    int[][][] allConditionsIndex = getConditions();

    int capacity = (maxSection - minSection + 1) * SectionBloomStore.blockPerSection;
    BitSet blockNumBitSet = new BitSet(capacity);
    blockNumBitSet.set(0, capacity);

    //works serial
    for (int conditionIndex = 0; conditionIndex < allConditionsIndex.length; conditionIndex++) {
      BitSet bitSet = subMatch(allConditionsIndex[conditionIndex]);
      blockNumBitSet.and(bitSet);
    }

    for (int i = blockNumBitSet.nextSetBit(0); i >= 0; i = blockNumBitSet.nextSetBit(i + 1)) {
      // operate on index i here
      if (i == Integer.MAX_VALUE) {
        break; // or (i+1) would overflow
      }
      long blockNum = minSection * SectionBloomStore.blockPerSection + i;
      if (minBlock <= blockNum && blockNum <= maxBlock) {
        blockNumList.add(blockNum);
      }
    }

    if (blockNumList.size() >= MAX_RESULT) {
      throw new JsonRpcTooManyResultException(
          "query returned more than " + MAX_RESULT + " results");
    }

    return blockNumList;
  }

  /**
   * address -> subMatch0,
   * topic1 -> subMatch1, topic2 -> subMatch2, topic3 -> subMatch3, topic4 -> subMatch4
   * works serial, return a BitSet with capacity of section num * blockPerSection
   */
  private BitSet subMatch(int[][] bitIndexes) throws ExecutionException, InterruptedException {

    int capacity = (maxSection - minSection + 1) * SectionBloomStore.blockPerSection;
    BitSet subBitSet = new BitSet(capacity);

    for (int section = minSection; section <= maxSection; section++) {
      BitSet partialBitSet = partialMatch(bitIndexes, section);

      for (int i = partialBitSet.nextSetBit(0); i >= 0; i = partialBitSet.nextSetBit(i + 1)) {
        // operate on index i here
        if (i == Integer.MAX_VALUE) {
          break; // or (i+1) would overflow
        }
        int offset = (section - minSection) * SectionBloomStore.blockPerSection + i;
        subBitSet.set(offset);
      }
    }

    return subBitSet;
  }


  /**
   * every section has a compound query of sectionBloomStore, works parallel
   * "and" condition in second dimension of query, "or" condition in first dimension
   * return a BitSet whose capacity is blockPerSection
   */
  private BitSet partialMatch(final int[][] bitIndexes, int section)
      throws ExecutionException, InterruptedException {
    List<List<Future<BitSet>>> bitSetList = new ArrayList<>();

    for (int i = 0; i < bitIndexes.length; i++) {
      List<Future<BitSet>> futureList = new ArrayList<>();
      for (int j = 0; j < bitIndexes[i].length; j++) { //must be 3
        final int bitIndex = bitIndexes[i][j];
        Future<BitSet> bitSetFuture =
            sectionExecutor.submit(() -> sectionBloomStore.get(section, bitIndex));
        futureList.add(bitSetFuture);
      }
      bitSetList.add(futureList);
    }

    BitSet bitSet = new BitSet(SectionBloomStore.blockPerSection);

    for (List<Future<BitSet>> futureList : bitSetList) {
      // initial a BitSet with all 1
      BitSet subBitSet = new BitSet(SectionBloomStore.blockPerSection);
      subBitSet.set(0, SectionBloomStore.blockPerSection);
      // and condition in second dimension
      for (Future<BitSet> future : futureList) {
        BitSet one = future.get();
        if (one == null) { //match nothing
          subBitSet.clear();
          break;
        }
        // "and" condition in second dimension
        subBitSet.and(one);
      }
      // "or" condition in first dimension
      bitSet.or(subBitSet);
    }
    return bitSet;
  }

  /**
   * convert LogFilter to the condition as 3 dimension array
   */
  private int[][][] getConditions() {

    LogFilter logFilter = logFilterWrapper.getLogFilter();
    List<byte[][]> conditions = new ArrayList<>();
    if (ArrayUtils.isNotEmpty(logFilter.getContractAddresses())) {
      conditions.add(logFilter.getContractAddresses());
    }
    for (byte[][] topicList : logFilter.getTopics()) {
      if (ArrayUtils.isNotEmpty(topicList)) {
        conditions.add(topicList);
      }
    }

    int[][][] allConditionsIndex = new int[conditions.size()][][];

    for (int k = 0; k < conditions.size(); k++) {
      byte[][] conditionByte = conditions.get(k);

      int[][] bitIndexes = new int[conditionByte.length][];
      for (int j = 0; j < conditionByte.length; j++) {

        byte[] hash = Hash.sha3(conditionByte[j]);
        Bloom bloom = Bloom.create(hash);
        BitSet bs = BitSet.valueOf(bloom.getData());

        int[] bitIndex = new int[3]; //must same as the number of hash function in Bloom
        int nonZeroCount = 0;
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
          // operate on index i here
          if (i == Integer.MAX_VALUE) {
            break; // or (i+1) would overflow
          }
          bitIndex[nonZeroCount++] = i;
        }

        bitIndexes[j] = bitIndex;
      }
      allConditionsIndex[k] = bitIndexes;
    }

    return allConditionsIndex;
  }
}
