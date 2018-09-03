/* Copyright 2017-2018 All Rights Reserved.
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * [Contact]
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0(the "License");
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

#ifndef INC_NETWORK_SWITCHER_H_
#define INC_NETWORK_SWITCHER_H_

#include <Core.h>
#include <DebugLog.h>

#include <mutex>
#include <thread>

#define kSegThreshold 512
#define kSegQueueThreshold 50 * (kSegThreshold / 512)

// Network Switcher Configs
#define METRIC_WINDOW_LENGTH 8
#define SLEEP_USECS (250 * 1000)

namespace sc {
typedef enum {
  kNSStateInitialized = 0,
  kNSStateRunning = 1,
  kNSStateSwitching = 2,
} NSState;

typedef enum {
  kNSModeEnergyAware = 0, /* WearDrive-like */
  kNSModeLatencyAware = 1, /* Selective Connection Unique */
  kNSModeCapDynamic = 2 /* CoolSpots */
} NSMode;

class SwitchAdapterTransaction {
  /*
   * Switch Adapter Transaction: Order
   * 1. NetworkSwitcher.switch_adapters()
   * 2. SwitchAdapterTransaction.start()
   * 3. next_adapter.connect() or next_adapter.wake_up()
   * 4. SwitchAdapterTransaction.connect_callback() or
   * SwitchAdapterTransaction.wakeup_callback()
   * 5. prev_adapter.disconnect() or prev_adapter.sleep()
   * 6. SwitchAdapterTransaction.disconnect_callback() or
   * SwitchAdapterTransaction.sleep_callback()
   * 7. NetworkSwitcher.done_switch()
   */
public:
  static bool run(int prev_index, int next_index);
  void start(void);
  static void connect_callback(bool is_success);
  static void disconnect_callback(bool is_success);

protected:
  void done(bool is_success);

  SwitchAdapterTransaction(int prev_index, int next_index) {
    this->mPrevIndex = prev_index;
    this->mNextIndex = next_index;
  }
  static SwitchAdapterTransaction *sOngoing;

  int mPrevIndex;
  int mNextIndex;
};

class ConnectRequestTransaction {
public:
  static bool run(int adapter_id);
  bool start(void);
  static void connect_callback(bool is_success);

protected:
  void done();

  ConnectRequestTransaction(int adapter_id) { this->mAdapterId = adapter_id; }
  static ConnectRequestTransaction *sOngoing;

  int mAdapterId;
};

class ReconnectControlAdapterTransaction {
public:
  static bool run();
  bool start();
  static void disconnect_callback(bool is_success);
  static void connect_callback(bool is_success);

protected:
  void done(bool require_restart);
  // static void on_fail(bool is_restart);

  ReconnectControlAdapterTransaction() {}
  static ReconnectControlAdapterTransaction *sOngoing;
};

class Stats {
public:
  /* Statistics used to print present status */
  int ema_queue_arrival_speed = 0;

  /* Statistics used in CoolSpots Policy */
  int now_total_bandwidth = 0;

  /* Statistics used in Energy-aware & Latency-aware Policy */
  int ema_send_request_size = 0;
  int ema_arrival_time_us = 0;
  int now_queue_data_size = 0;
};

class Core;
class NetworkSwitcher {
public:
  /* Control netwowrk switcher thread */
  void start(void);
  void stop(void);

  /* Get state */
  NSState get_state(void) {
    std::unique_lock<std::mutex> lck(this->mStateLock);
    return this->mState;
  }

  /* Get or set mode */
  NSMode get_mode(void) {
    std::unique_lock<std::mutex> lck(this->mModeLock);
    NSMode mode = this->mMode;
    return mode;
  }

  void set_mode(NSMode new_mode) {
    std::unique_lock<std::mutex> lck(this->mModeLock);
    this->mMode = new_mode;
  }

  /*
   * Connect adapter command.
   * It is called by peer through Core.
   */
  void connect_adapter(int adapter_id);

  /*
   * Sleep adapter command.
   * It is called by peer through Core.
   */
  bool sleep_adapter(int adapter_id);

  /*
   * Wake up adapter command.
   * It is called by peer through Core.
   */
  bool wake_up_adapter(int adapter_id);

  /*
   * Reconnect control adapter command.
   * It is called by Core.
   */
  void reconnect_control_adapter(void);

  /* Notification of switch done event */
  void done_switch() {
    LOG_VERB("Switch adapter end!");
    NSState state = this->get_state();
    switch (state) {
    case NSState::kNSStateSwitching:
      this->set_state(NSState::kNSStateRunning);
      break;
    case NSState::kNSStateInitialized:
    case NSState::kNSStateRunning:
      break;
    }
  }

  /* Singleton */
  static NetworkSwitcher *get_instance(void) {
    if (NetworkSwitcher::singleton == NULL) {
      NetworkSwitcher::singleton = new NetworkSwitcher();
    }
    return NetworkSwitcher::singleton;
  }

private:
  /* Singleton */
  static NetworkSwitcher *singleton;
  NetworkSwitcher(void) {
    this->mSwitcherThreadOn = false;
    this->mThread = NULL;
    this->set_state(NSState::kNSStateInitialized);
    this->mBandwidthWhenIncreasing = 0;
    this->mDecreasingCheckCount = 0;
    this->mActiveDataAdapterIndex = 0;
    this->set_mode(NSMode::kNSModeEnergyAware);
  }

  /* Network switcher thread */
  void switcher_thread(void);

  /* Monitoring */
  void get_stats(Stats &stats);
  void print_stats(Stats &stats);
  void check_and_handover(Stats &stats);

  int get_init_energy_payoff_point(void);
  int get_idle_energy_payoff_point(int avg_arrival_time_us);
  int get_init_latency_payoff_point(void);
  bool check_increase_adapter(const Stats &stats);
  bool check_decrease_adapter(const Stats &stats);

  /* Switch adapters */
  bool increase_adapter(void);
  bool decrease_adapter(void);
  bool switch_adapters(int prev_index, int next_index);
  bool is_increaseable(void);
  bool is_decreaseable(void);

private:
  /*
   * Active Data Adapter Index means the index value indicating
   * 'conencted' or 'connecting' data adapter currently.
   * Only "the current data adapter" is 'connected' or 'connecting',
   * but the others are 'connected(but to-be-disconnected)', 'disconnected' or
   * 'disconnecting'. This index is changed right before increasing or
   * decreasing starts.
   */
  int mActiveDataAdapterIndex;

public:
  int get_active_data_adapter_index(void) {
    return this->mActiveDataAdapterIndex;
  }

  void set_active_data_adapter_index(int active_data_adapter_index) {
    this->mActiveDataAdapterIndex = active_data_adapter_index;
  }

private:
  void set_state(NSState new_state) {
    std::unique_lock<std::mutex> lck(this->mStateLock);
    this->mState = new_state;
  }

  std::thread *mThread;

  bool mSwitcherThreadOn;
  NSState mState;
  std::mutex mStateLock;
  NSMode mMode;
  std::mutex mModeLock;
  int mBandwidthWhenIncreasing;
  int mDecreasingCheckCount;

  Counter mQueueArrivalSpeed; /* to achieve the ema of queue arrival speed */
};
} /* namespace sc */

#endif /* INC_NETWORK_SWITCHER_H_ */
