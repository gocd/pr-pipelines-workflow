/*
 * Copyright 2024 Thoughtworks, Inc.
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

import cd.go.contrib.plugins.configrepo.groovy.dsl.GitMaterial
import cd.go.contrib.plugins.configrepo.groovy.dsl.GoCD


def baseMaterial = new GitMaterial({
  username = 'gocd-ci-user'

  encryptedPassword = 'AES:kkJW5FtX9br8TT8Hw/4Sqg==:0kq8aa8bu9XtD7reHPmc+NGRF6fp1GSWdSEesz/59dPCYgeXReXkWyL/RwpqBESHQyZBYtUwlcjKaBpu9Eo1kQ=='
  //githubPassword = '{{SECRET:[build-pipelines][GOCD_CI_USER_RELEASE_TOKEN]}}' - above is encrypted due to GoCD requirements
})

def branches = [
//  [
//    url: "https://github.com/gocd/some-private-repo.git",
//    name: 'branch-1',
//  ],
//  [
//    url: "https://github.com/gocd/some-private-repo.git",
//    name: 'branch-2',
//  ],
//  [
//    url: "https://github.com/gocd/some-private-repo2.git",
//    name: 'branch-3',
//  ],
]

def groupName = { branchName -> "private-${branchName}" }

GoCD.script {
  pipelines {
    branches.each { b ->

      def branchMaterial = baseMaterial.dup({
        url = b.url
        name = "gocd_${b.name}"
        branch = b.name
      })

      pipeline("build-linux-${b.name}") {
        group = groupName(b.name)
        template = 'build-gradle-linux'
        materials {
          add(branchMaterial)
        }
      }

      pipeline("plugins-${b.name}") {
        group = groupName(b.name)
        template = 'plugins-gradle'

        materials {
          add(branchMaterial.dup({
            destination = 'gocd'
          }))

          git('go-plugins') {
            url = 'https://git.gocd.io/git/gocd/go-plugins'
            shallowClone = true
            destination = 'go-plugins'
            blacklist = ['**/*']
          }

          dependency('linux') {
            pipeline = "build-linux-${b.name}"
            stage = 'build-server'
          }
        }
      }

      pipeline("installers-${b.name}") {
        group = groupName(b.name)
        template = 'installers-gradle'

        materials {
          add(branchMaterial)

          dependency('go-plugins') {
            pipeline = "plugins-${b.name}"
            stage = 'build'
          }
        }
        environmentVariables = [
          UPDATE_GOCD_BUILD_MAP: 'Y',
        ]
        params = ['plugins-pipeline-name': String.format("plugins-%s", b.name)]
      }

      pipeline("smoke-${b.name}") {
        group = groupName(b.name)
        template = 'smoke-gradle'

        materials {
          dependency('installers') {
            pipeline = "installers-${b.name}"
            stage = 'docker'
          }

          git('ruby-functional-tests') {
            url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
            shallowClone = true
            blacklist = ['**/*']
          }
        }
        environmentVariables = [
          AGENT_MEM              : '64m',
          AGENT_MAX_MEM          : '512m',
          ADDITIONAL_STARTUP_ARGS: '-Dgocd.environments.show.pipelines=Y',
          RAILS_LOG_LEVEL        : 'debug',
        ]
        params = [
          'installers-pipeline-name': String.format("installers-%s", b.name),
          'plugins-pipeline-name'   : String.format("plugins-%s/installers-%s", b.name, b.name),
        ]
      }

      pipeline("regression-SPAs-${b.name}") {
        group = groupName(b.name)
        template = 'regression-ruby-webdriver'

        materials {
          dependency('smoke') {
            pipeline = "smoke-${b.name}"
            stage = 'Smoke'
            ignoreForScheduling = true
          }

          git('ruby-functional-tests') {
            url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
            shallowClone = true
            blacklist = ['**/*']
          }
        }
        environmentVariables = [
          browser                : 'firefox',
          ADDITIONAL_STARTUP_ARGS: '-Dgocd.environments.show.pipelines=Y',
          RAILS_LOG_LEVEL        : 'debug',
        ]
        params = [
          'installers-pipeline-name': String.format("installers-%s/smoke-%s", b.name, b.name),
          'plugins-pipeline-name'   : String.format("plugins-%s/installers-%s/smoke-%s", b.name, b.name, b.name),
          spa_tags                  : 'spa,!agentspage',
          regression_tags           : '!wip,!smoke,!api,!agent_manual_registration,!bundled-auth-plugins,!run-on-docker,!spa,!smoke,!elastic_agent_profile,!maintenance_mode,!tfs,!analytics,!vsm_analytics,!create pipeline',
          bundled_auth_plugins      : 'bundled-auth-plugins',
          api_tags                  : 'api',
          maintenance_mode_tags     : 'maintenance_mode',
        ]
      }
    }
  }

  // gather all the pipelines generated to this point so we can add
  // them to the gocd environment
  def pipelineNames = pipelines.collect { p -> p.name }

  environments {
    environment('gocd') {
      pipelines = pipelineNames // add all pipelines
    }
  }
}
