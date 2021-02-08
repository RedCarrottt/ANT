/* Copyright (c) 2017-2021 SKKU ESLAB, and contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Gateway Virtual Sensor API: deep sensor example

var ant = require('ant');
var console = require('console');
var gateway = require('gateway');
var fs = require('fs');

// VSA: virtual sensor adapter
var gVSA = undefined;

// Config
var kFilename = 'image.jpg';
 
/* Deep sensor handlers */
// TODO: pre-processing handler
// TODO: post-processing handler

/* ANT lifecycle handlers */
function onInitialize() {
  // Empty function
}

function onStart() {
  gVSA = gateway.getVSAdapter();
  gVSA.createDeepSensor('d1', 'mobilenetv1', 13);
  gVSA.start();
}

function onStop() {
  gVSA.stop();
}

ant.runtime.setCurrentApp(onInitialize, onStart, onStop);