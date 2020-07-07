/*
 * Copyright 2020 ThoughtWorks, Inc.
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

import cd.go.contrib.plugins.configrepo.groovy.dsl.GoCD

GoCD.script {
  branches {
    matching {
      // OPTIONAL: defaults to match any string. When present,
      // this will filter/restrict git refs to those that match
      // the provided pattern.
      pattern = ~/.*/

      from = github {
        fullRepoName = "gocd/gocd"
        materialUrl = "https://git.gocd.io/git/${fullRepoName}"
      }

      onMatch { ctx ->
        // Build your entire workflow; you can have many pipeline blocks here.
        pipeline("build-linux-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          materials { add(ctx.repo) }
          template = 'build-gradle-linux'
          params = [OS: 'linux', BROWSER: 'firefox']
        }

        pipeline("build-windows-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          materials { add(ctx.repo) }
          template = 'build-gradle-windows'
          params = [OS: 'windows', BROWSER: 'msedge']
        }
      }
    }
  }
}
