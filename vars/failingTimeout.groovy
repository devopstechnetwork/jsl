/*
 * Copyright (C) 2012-2019, TomTom (http://tomtom.com).
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
def call(Map conf, Closure body) {
    // Modified from https://support.cloudbees.com/hc/en-us/articles/226554067/comments/360000870712
    try {
        timeout(conf) {
            body()
        }
    } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException err) {
        errorCause = err.causes.get(0)
        // Specially handle the timeout case
        if(errorCause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) { // SYSTEM means timeout.
            error "Build failed due to timeout."
        } else {
            throw err
        }
    }
}