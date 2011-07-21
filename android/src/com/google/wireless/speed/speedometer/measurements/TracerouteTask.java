// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.wireless.speed.speedometer.measurements;

import com.google.wireless.speed.speedometer.MeasurementDesc;
import com.google.wireless.speed.speedometer.MeasurementError;
import com.google.wireless.speed.speedometer.MeasurementResult;
import com.google.wireless.speed.speedometer.MeasurementTask;
import com.google.wireless.speed.speedometer.PhoneUtils;
import com.google.wireless.speed.speedometer.R;
import com.google.wireless.speed.speedometer.SpeedometerApp;
import com.google.wireless.speed.speedometer.util.MeasurementJsonConvertor;
import com.google.wireless.speed.speedometer.util.RuntimeUtil;
import com.google.wireless.speed.speedometer.util.Util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InvalidClassException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;

/**
 * A Callable task that handles Traceroute measurements
 * @author wenjiezeng@google.com (Steve Zeng)
 *
 */
public class TracerouteTask extends MeasurementTask {

  public static final String TYPE = "traceroute";
  /* Default payload size of the ICMP packet, plus the 8-byte ICMP header resulting in a total of 
   * 64-byte ICMP packet */
  public static final int DEFAULT_PING_PACKET_SIZE = 56;
  // Default # of pings per ping task
  public static final int DEFAULT_PING_CNT_PER_TASK = 10;
  public static final double DEFAULT_PING_INTERVAL = 0.5;
  public static final int DEFAULT_PING_TIMEOUT = 10;
  public static final int DEFAULT_MAX_PING_CNT = 10;
  public static final int DEFAULT_PINGS_PER_HOP = 3;
  private static final long DEFAULT_CNT = 1;
  
  /**
   * The description of the Traceroute measurement 
   */
  public static class TracerouteDesc extends MeasurementDesc {
    // the host name or IP address to use as the target of the traceroute.
    private String target;
    // the packet per ICMP ping in the unit of bytes
    private int packetSizeByte;
    // the number of seconds we wait for a ping response.
    private int pingTimeoutSec;
    // the interval between successive pings in seconds
    private double pingIntervalSec; 
    // the number of pings we use for each ttl value
    private int pingsPerHop;        
    // the total number of pings will send before we declarethe traceroute fails
    private int maxPingCount;        
    // the location of the ping binary. Only used internally
    private String pingExe;         
    
    public TracerouteDesc(String key, Date startTime,
                          Date endTime, double intervalSec, long count, long priority, 
                          Map<String, String> params) throws InvalidParameterException {
      super(TracerouteTask.TYPE, key, startTime, endTime, intervalSec, count, 
          priority, params);
      initalizeParams(params);
      
      if (target == null) {
        throw new InvalidParameterException("Target of traceroute cannot be null");
      }
    }

    @Override
    public String getType() {
      return TracerouteTask.TYPE;
    }
    
    @Override
    protected void initalizeParams(Map<String, String> params) {
      
      if (params == null) {
        return;
      }
      // Common parameters that every MeasurementDesc has
      if (this.count == 0) {
        this.count = TracerouteTask.DEFAULT_CNT;
      }      

      if (this.intervalSec == 0) {
        this.intervalSec = TracerouteTask.DEFAULT_PING_INTERVAL;
      }
      
      // HTTP specific parameters according to the design document
      this.target = params.get("target");
      try {        
        String val;
        if ((val = params.get("packet_size_byte")) != null) {
          this.packetSizeByte = Integer.parseInt(val);  
        } else {
          this.packetSizeByte = TracerouteTask.DEFAULT_PING_PACKET_SIZE;
        }
        if ((val = params.get("ping_timeout_sec")) != null) {
          this.pingTimeoutSec = Integer.parseInt(val);  
        } else {
          this.pingTimeoutSec = TracerouteTask.DEFAULT_PING_TIMEOUT;
        }
        if ((val = params.get("ping_interval_sec")) != null) {
          this.pingIntervalSec = Integer.parseInt(val);  
        } else {
          this.pingIntervalSec = TracerouteTask.DEFAULT_PING_INTERVAL;
        }
        if ((val = params.get("pings_per_hop")) != null) {
          this.pingsPerHop = Integer.parseInt(val);  
        } else {
          this.pingsPerHop = TracerouteTask.DEFAULT_PINGS_PER_HOP;
        }
        if ((val = params.get("max_ping_count")) != null) {
          this.maxPingCount = Integer.parseInt(val);  
        } else {
          this.maxPingCount = TracerouteTask.DEFAULT_MAX_PING_CNT;
        }
      } catch (NumberFormatException e) {
        throw new InvalidParameterException("PingTask cannot be created due to invalid params");
      }
    }
    
  }
  
  public TracerouteTask(MeasurementDesc desc, SpeedometerApp parent) {
    super(new TracerouteDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
      desc.count, desc.priority, desc.parameters), parent);
  }

  @Override
  public MeasurementResult call() throws MeasurementError {
    TracerouteDesc task = (TracerouteDesc) this.measurementDesc;
    int maxPingCnt = task.maxPingCount;
    int ttl = 1;
    String hostIp = null;
    Process pingProc = null;
    // TODO(Wenjie): Add a exhaustive list of ping locations for different Android phones
    task.pingExe = parent.getString(R.string.ping_executable);
    String target = task.target;
    boolean success = false;
    ArrayList<HopInfo> hopHosts = new ArrayList<HopInfo>();
    
    Log.d(SpeedometerApp.TAG, "Starting traceroute on host " + task.target);
    
    
    try {
      hostIp = InetAddress.getByName(target).getHostAddress();
    } catch (UnknownHostException e) {
      Log.e(SpeedometerApp.TAG, "Cannont resolve host " + target);
      throw new MeasurementError("target " + target + " cannot be resolved");
    }
    MeasurementResult result = null;
    
    try {
      PhoneUtils.getPhoneUtils().acquireWakeLock();
      while (maxPingCnt-- >= 0) {
        /* Current traceroute implementation sends out three ICMP probes per TTL.
         * One ping every 0.2s is the lower bound before some platforms requires
         * root to run ping. We ping once every time to get a rough rtt as we cannot
         * get the exact rtt from the output of the ping command with ttl being set
         * */        
        String command = Util.constructCommand(task.pingExe, "-n", "-t", ttl,
            "-s", task.packetSizeByte, "-c 1 ", target);
        try {
          double rtt = 0;
          long t1;
          HashSet<String> hostsAtThisDistance = new HashSet<String>();
          for (int i = 0; i < task.pingsPerHop; i++) {
            t1 = System.currentTimeMillis();
            pingProc = Runtime.getRuntime().exec(command);
            rtt += System.currentTimeMillis() - t1;
            // Grab the output of the process that runs the ping command
            InputStream is = pingProc.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            
            /* Process each line of the ping output and extracts the intermediate hops into 
             * hostAtThisDistance */ 
            processPingOutput(br, hostsAtThisDistance, hostIp);
            cleanUp(pingProc);
            try {
              Thread.sleep((long) (task.pingIntervalSec * 1000));
            } catch (InterruptedException e) {
              Log.i(SpeedometerApp.TAG, "Sleep interrupted between ping intervals");
            }
          }
          rtt = rtt / task.pingsPerHop;
  
          hopHosts.add(new HopInfo(hostsAtThisDistance, rtt));
          
          // Process the extracted IPs of intermediate hops
          StringBuffer progressStr = new StringBuffer(ttl + ": ");
          for (String ip : hostsAtThisDistance) {
            // If we have reached the final destination hostIp, print it out and clean up
            if (ip.compareTo(hostIp) == 0) {
              Log.i(SpeedometerApp.TAG, ttl + ": " + hostIp);
              Log.i(SpeedometerApp.TAG, " Finished! " + target + " reached in " + ttl + " hops");
              
              success = true;
              cleanUp(pingProc);
              result = new MeasurementResult(RuntimeUtil.getDeviceInfo().deviceId, 
                  RuntimeUtil.getDeviceProperty(), TracerouteTask.TYPE, 
                  Calendar.getInstance().getTime(), success, this.measurementDesc);
              result.addResult("num_hops", ttl);
              for (int i = 0; i < hopHosts.size(); i++) {
                HopInfo hopInfo = hopHosts.get(i);
                int hostIdx = 1;
                for (String host : hopInfo.hosts) {
                  result.addResult("hop_" + i + "_addr_" + hostIdx++, host);
                }
                result.addResult("hop_" + i + "_rrt_ms", String.format("%.3f", hopInfo.rtt));
              }
              Log.i(SpeedometerApp.TAG, MeasurementJsonConvertor.toJsonString(result));
              return result;
            } else {
              // Otherwise, we aggregate various hosts at a given hop distance for printout
              progressStr.append(ip + " | ");
            }
          }
          // Remove the trailing separators
          progressStr.delete(progressStr.length() - 3, progressStr.length());
          Log.i(SpeedometerApp.TAG, progressStr.toString());
                  
        } catch (SecurityException e) {
          Log.e(SpeedometerApp.TAG, "Does not have the permission to run ping on this device");
        } catch (IOException e) {
          Log.e(SpeedometerApp.TAG, "The ping program cannot be executed");
          Log.e(SpeedometerApp.TAG, e.getMessage());
        } finally {
          cleanUp(pingProc);
        }
        ttl++;
      }
    } finally {
      PhoneUtils.getPhoneUtils().shutDown();
    }
    throw new MeasurementError("cannot perform traceroute to " + task.target);
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return TracerouteDesc.class;
  }
  
  @Override
  public String getType() {
    return TracerouteTask.TYPE;
  }
  
  private void cleanUp(Process proc) {
    if (proc != null) {
      // destroy() closes all open streams
      proc.destroy();
    }      
  }

  private void processPingOutput(BufferedReader br, HashSet<String> hostsAtThisDistance,
      String hostIp) throws IOException {
    String line = null;
    while ((line = br.readLine()) != null) {
      Log.d(SpeedometerApp.TAG, line);
      if (line.startsWith("From")) {
        String ip = getHostIp(line);
        if (ip != null && ip.compareTo(hostIp) != 0) {
          hostsAtThisDistance.add(ip);
        }
      } else if (line.contains("time=")) {
        hostsAtThisDistance.add(hostIp);
      }
    }
  }

  /* TODO(Wenjie): The current search for valid IPs assumes the IP string is not a proper 
   * substring of the space-separated tokens. For more robust searching in case different 
   * outputs from ping due to its different versions, we need to refine the search 
   * by testing weather any substring of the tokens contains a valid IP */
  private String getHostIp(String line) {      
    String[] tokens = line.split(" ");
    // In most cases, the second element in the array is the IP
    String tempIp = tokens[1];
    if (isValidIpv4Addr(tempIp)) {
      return tempIp;
    } else {
      for (int i = 0; i < tokens.length; i++) {
        if (i == 1) {
          // Examined already
          continue;
        } else {
          if (isValidIpv4Addr(tokens[i])) {
            return tokens[i];
          }
        }
      }
    }
    
    return null;
  }
  
  // Tells whether the string is an valid IPv4 address
  private boolean isValidIpv4Addr(String ip) {
    String[] tokens = ip.split("\\.");
    if (tokens.length == 4) {
      for (int i = 0; i < 4; i++) {
        try {
          int val = Integer.parseInt(tokens[i]); 
          if (val < 0 || val > 255) {
            return false;
          }
        } catch (NumberFormatException e) {
          Log.d(SpeedometerApp.TAG, ip + " is not a valid IP address");
          return false;
        }
      }
      return true;
    }
    return false;
  }  
  
  private class HopInfo {
    // The hosts at a given hop distance
    public HashSet<String> hosts;
    // The average RRT for this hop distance
    public double rtt;
    
    protected HopInfo(HashSet<String> hosts, double rtt) {
      this.hosts = hosts;
      this.rtt = rtt;
    }
  }
}