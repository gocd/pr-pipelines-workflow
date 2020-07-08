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


import cd.go.contrib.plugins.configrepo.groovy.dsl.BranchContext
import cd.go.contrib.plugins.configrepo.groovy.dsl.GitMaterial
import cd.go.contrib.plugins.configrepo.groovy.dsl.GoCD

final List<String> pipeNames = []

GoCD.script {
  branches {
    matching {
      from = github {
        fullRepoName = 'gocd/gocd'
        materialUrl = "https://git.gocd.io/git/${fullRepoName}"
      }

      onMatch { BranchContext ctx ->
        pipeline("build-linux-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'build-gradle-linux'
          materials { add(ctx.repo) }
          params = [OS: 'linux', BROWSER: 'firefox']
        }

        pipeline("build-windows-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'build-gradle-windows'
          materials { add(ctx.repo) }
          params = [OS: 'windows', BROWSER: 'msedge']
        }

        pipeline("plugins-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'plugins-gradle'

          materials {
            add((ctx.repo as GitMaterial).dup({
              name = 'gocd'
              destination = name
            }))

            git('go-plugins') {
              url = 'https://git.gocd.io/git/gocd/go-plugins'
              shallowClone = true
              destination = 'go-plugins'
            }

            dependency('linux') {
              pipeline = "build-linux-${ctx.branchSanitized}"
              stage = 'build-server'
            }

            dependency('windows') {
              pipeline = "build-windows-${ctx.branchSanitized}"
              stage = 'build-server'
            }
          }
        }

        pipeline("installers-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'installers-gradle'

          materials {
            add((ctx.repo as GitMaterial).dup({
              name = 'gocd'
              destination = name
            }))

            dependency('go-plugins') {
              pipeline = "plugins-${ctx.branchSanitized}"
              stage = 'build'
            }
          }
          environmentVariables = [
            UPDATE_GOCD_BUILD_MAP: 'Y',
            WINDOWS_64BIT_JDK_URL: 'https://nexus.gocd.io/repository/s3-mirrors/local/jdk/openjdk-11.0.2_windows-x64_bin.zip',
            WINDOWS_JDK_URL      : 'https://nexus.gocd.io/repository/s3-mirrors/local/jdk/openjdk-11.0.2_windows-x64_bin.zip',
          ]
          params = ['plugins-pipeline-name': String.format("plugins-%s", ctx.branchSanitized)]
        }

        pipeline("smoke-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'smoke-gradle'

          materials {
            dependency('installers') {
              pipeline = "installers-${ctx.branchSanitized}"
              stage = 'docker'
            }

            git('ruby-functional-tests') {
              url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
              destination = name
            }
          }
          environmentVariables = [
            AGENT_MEM              : '64m',
            AGENT_MAX_MEM          : '512m',
            GO_VERSION             : '20.6.0',
            ADDITIONAL_STARTUP_ARGS: '-Dgocd.environments.show.pipelines=Y',
            RAILS_LOG_LEVEL        : 'debug',
          ]
          params = [
            'installers-pipeline-name': String.format("installers-%s", ctx.branchSanitized),
            'plugins-pipeline-name'   : String.format("plugins-%s/installers-%s", ctx.branchSanitized, ctx.branchSanitized),
            'sahi-working-dir'        : 'sahi-tests',
            'selenium-working-dir'    : 'selenium-tests',
          ]
        }

        pipeline("regression-SPAs-${ctx.branchSanitized}") {
          group = "gocd-${ctx.branchSanitized}"
          template = 'regression-ruby-webdriver'

          materials {
            dependency('smoke') {
              pipeline = "smoke-${ctx.branchSanitized}"
              stage = 'Smoke'
            }

            git('ruby-functional-tests') {
              url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
              destination = name
            }
          }
          environmentVariables = [
            GO_VERSION                   : '20.6.0',
            browser                      : 'firefox',
            ADDITIONAL_STARTUP_ARGS      : '-Dgocd.environments.show.pipelines=Y',
            SHINE_ENABLED                : 'Y',
            RAILS_LOG_LEVEL              : 'debug',
            GO_DISALLOW_PROPERTIES_ACCESS: 'false',
          ]
          params = [
            spa_tags                  : 'SPA,!agentspage,!agentsfilter',
            regression_tags           : '!SPA,!smoke,!agent_manual_registration,!bundled-auth-plugins,!analytics,!agentsfilter,!api,!VSM_analytics,!wip,!roles-spa,!drain_mode,!WIP,!elastic_agent,!elastic_agent_profile,!run-on-docker,!elastic_agent_profiles,!tfs,!hg credentials',
            'installers-pipeline-name': String.format("installers-%s/smoke-%s", ctx.branchSanitized, ctx.branchSanitized),
            'plugins-pipeline-name'   : String.format("plugins-%s/installers-%s/smoke-%s", ctx.branchSanitized, ctx.branchSanitized, ctx.branchSanitized),
            bundled_auth_plugins      : 'bundled-auth-plugins',
            api_tags                  : '!SPA,!smoke,!agent_manual_registration,!pipeline_selector,!bundled-auth-plugins,!analytics,!external_artifacts,api',
            maintenance_mode_tags     : 'drain_mode',
            elastic_agents_tags       : 'elastic_agent',
          ]
        }

      }
    }
  }

  pipeNames.addAll(pipelines.collect { p -> p.name })

  environments {
    environment("gocd") {
      pipelines = pipeNames
    }
  }
}
