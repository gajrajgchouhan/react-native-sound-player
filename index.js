/**
 * @flow
 */
"use strict";

import { NativeModules, NativeEventEmitter, Platform } from "react-native";
import resolveAsset from "react-native/Libraries/Image/resolveAssetSource";
const { RNSoundPlayer } = NativeModules;

const _soundPlayerEmitter = new NativeEventEmitter(RNSoundPlayer);
let _finishedPlayingListener = null;
let _finishedLoadingListener = null;

export default {
  playSoundFile: (name, type) => {
    RNSoundPlayer.playSoundFile(name, type);
  },

  playSoundFileWithDelay: (name, type, delay) => {
    RNSoundPlayer.playSoundFileWithDelay(name, type, delay);
  },

  loadSoundFile: (name, type) => {
    RNSoundPlayer.loadSoundFile(name, type);
  },

  setNumberOfLoops: (loops) => {
    RNSoundPlayer.setNumberOfLoops(loops);
  },

  playUrl: (url) => {
    RNSoundPlayer.playUrl(url);
  },

  loadUrl: (url) => {
    RNSoundPlayer.loadUrl(url);
  },

  playUrlWithStreaming: (url) => {
    RNSoundPlayer.playUrlWithStreaming(url);
  },

  loadUrlWithStreaming: (url) => {
    RNSoundPlayer.loadUrlWithStreaming(url);
  },

  playUrlWithStreamingEncrypted: (url, dekHex, counterBaseHex) => {
    RNSoundPlayer.playUrlWithStreamingEncrypted(url, dekHex, counterBaseHex);
  },

  loadUrlWithStreamingEncrypted: (url, dekHex, counterBaseHex) => {
    RNSoundPlayer.loadUrlWithStreamingEncrypted(url, dekHex, counterBaseHex);
  },

  playAsset: async (asset) => {
    if (!__DEV__ && Platform.OS === "android") {
      RNSoundPlayer.playSoundFile(resolveAsset(asset).uri, "");
    } else {
      RNSoundPlayer.playUrl(resolveAsset(asset).uri);
    }
  },

  loadAsset: (asset) => {
    if (!__DEV__ && Platform.OS === "android") {
      RNSoundPlayer.loadSoundFile(resolveAsset(asset).uri, "");
    } else {
      RNSoundPlayer.loadUrl(resolveAsset(asset).uri);
    }
  },

  onFinishedPlaying: (callback) => {
    if (_finishedPlayingListener) {
      _finishedPlayingListener.remove();
      _finishedPlayingListener = undefined;
    }

    _finishedPlayingListener = _soundPlayerEmitter.addListener(
      "FinishedPlaying",
      callback
    );
  },

  onFinishedLoading: (callback) => {
    if (_finishedLoadingListener) {
      _finishedLoadingListener.remove();
      _finishedLoadingListener = undefined;
    }

    _finishedLoadingListener = _soundPlayerEmitter.addListener(
      "FinishedLoading",
      callback
    );
  },

  addEventListener: (eventName, callback) =>
    _soundPlayerEmitter.addListener(eventName, callback),

  play: () => {
    // play and resume has the exact same implementation natively
    RNSoundPlayer.resume();
  },

  pause: () => {
    RNSoundPlayer.pause();
  },

  resume: () => {
    RNSoundPlayer.resume();
  },

  stop: () => {
    RNSoundPlayer.stop();
  },

  seek: (seconds) => {
    RNSoundPlayer.seek(seconds);
  },

  setVolume: (volume) => {
    RNSoundPlayer.setVolume(volume);
  },

  setSpeaker: (on) => {
    RNSoundPlayer.setSpeaker(on);
  },

  setMixAudio: (on) => {
    if (Platform.OS === "android") {
      console.log("setMixAudio is not implemented on Android");
    } else {
      RNSoundPlayer.setMixAudio(on);
    }
  },

  getInfo: async () => RNSoundPlayer.getInfo(),

  unmount: () => {
    if (_finishedPlayingListener) {
      _finishedPlayingListener.remove();
      _finishedPlayingListener = undefined;
    }

    if (_finishedLoadingListener) {
      _finishedLoadingListener.remove();
      _finishedLoadingListener = undefined;
    }
  },
};
