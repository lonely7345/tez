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

package org.apache.tez.mapreduce.examples;

import java.io.IOException;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.hadoop.MultiStageMRConfigUtil;

/**
 * Simple example that does a GROUP BY ORDER BY in an MRR job
 * Consider a query such as
 * Select DeptName, COUNT(*) as cnt FROM EmployeeTable
 * GROUP BY DeptName ORDER BY cnt;
 *
 * i.e. List all departments with count of employees in each department
 * and ordered based on department's employee count.
 *
 *  Requires an Input file containing 2 strings per line in format of
 *  <EmployeeName> <DeptName>
 *
 *  For example, use the following:
 *
 *  #/bin/bash
 *
 *  i=1000000
 *  j=1000
 *
 *  id=0
 *  while [[ "$id" -ne "$i" ]]
 *  do
 *    id=`expr $id + 1`
 *    deptId=`expr $RANDOM % $j + 1`
 *    deptName=`echo "ibase=10;obase=16;$deptId" | bc`
 *    echo "$id O$deptName"
 *  done
 *
 */
public class GroupByOrderByMRRTest {

  /**
   * Mapper takes in a single line as input containing
   * employee name and department name and then
   * emits department name with count of 1
   */
  public static class MyMapper
      extends Mapper<Object, Text, Text, IntWritable> {

    private final static IntWritable one = new IntWritable(1);
    private final static Text word = new Text();

    public void map(Object key, Text value, Context context
        ) throws IOException, InterruptedException {
      StringTokenizer itr = new StringTokenizer(value.toString());
      String empName = "";
      String deptName = "";
      if (itr.hasMoreTokens()) {
        empName = itr.nextToken();
        if (itr.hasMoreTokens()) {
          deptName = itr.nextToken();
        }
        if (!empName.isEmpty()
            && !deptName.isEmpty()) {
          word.set(deptName);
          context.write(word, one);
        }
      }
    }
  }

  /**
   * Intermediate reducer aggregates the total count per department.
   * It takes department name and count as input and emits the final
   * count per department name.
   */
  public static class MyGroupByReducer
      extends Reducer<Text, IntWritable, IntWritable, Text> {
    private IntWritable result = new IntWritable();

    public void reduce(Text key, Iterable<IntWritable> values,
        Context context
        ) throws IOException, InterruptedException {

      int sum = 0;
      for (IntWritable val : values) {
        sum += val.get();
      }
      result.set(sum);
      context.write(result, key);
    }
  }

  /**
   * Shuffle ensures ordering based on count of employees per department
   * hence the final reducer is a no-op and just emits the department name
   * with the employee count per department.
   */
  public static class MyOrderByNoOpReducer
      extends Reducer<IntWritable, Text, Text, IntWritable> {

    public void reduce(IntWritable key, Iterable<Text> values,
        Context context
        ) throws IOException, InterruptedException {
      for (Text word : values) {
        context.write(word, key);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = new Configuration();

    // Configure intermediate reduces
    conf.setInt(MRJobConfig.MRR_INTERMEDIATE_STAGES, 1);

    // Set reducer class for intermediate reduce
    conf.setClass(MultiStageMRConfigUtil.getPropertyNameForIntermediateStage(1,
        "mapreduce.job.reduce.class"), MyGroupByReducer.class, Reducer.class);
    // Set reducer output key class
    conf.setClass(MultiStageMRConfigUtil.getPropertyNameForIntermediateStage(1,
        "mapreduce.map.output.key.class"), IntWritable.class, Object.class);
    // Set reducer output value class
    conf.setClass(MultiStageMRConfigUtil.getPropertyNameForIntermediateStage(1,
        "mapreduce.map.output.value.class"), Text.class, Object.class);
    conf.setInt(MultiStageMRConfigUtil.getPropertyNameForIntermediateStage(1,
        "mapreduce.job.reduces"), 2);

    String[] otherArgs = new GenericOptionsParser(conf, args).
        getRemainingArgs();
    if (otherArgs.length != 2) {
      System.err.println("Usage: groupbyorderbymrrtest <in> <out>");
      System.exit(2);
    }

    Job job = new Job(conf, "groupbyorderbymrrtest");

    job.setJarByClass(GroupByOrderByMRRTest.class);

    // Configure map
    job.setMapperClass(MyMapper.class);
    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(IntWritable.class);

    // Configure reduce
    job.setReducerClass(MyOrderByNoOpReducer.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(IntWritable.class);
    job.setNumReduceTasks(1);

    FileInputFormat.addInputPath(job, new Path(otherArgs[0]));
    FileOutputFormat.setOutputPath(job, new Path(otherArgs[1]));
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }

}
