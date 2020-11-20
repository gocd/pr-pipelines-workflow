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
import groovy.json.JsonSlurper

final JsonSlurper JSON = new JsonSlurper()

GoCD.script {
  final List<String> pullRequestPipelineNames = []

  Closure<Object> parse = { String key, Object defaultValue ->
    def value = lookup(key)
    return value.startsWith('JSON:') ? // this protects stubbed values during preflight
      JSON.parseText(value.substring('JSON:'.length())) :
      defaultValue
  }

  Closure<Boolean> manualTrigger = { BranchContext ctx ->
    def users = parse('manual.trigger.authors', []) as List<String>
    def labels = parse('manual.trigger.labels', []) as List<String>
    return users.contains(ctx.author) ||
      ctx.labels.any { item -> labels.contains(item) }
  }

  Closure<Boolean> includeWindows = { lookup("include.windows.pipelines").equalsIgnoreCase("true") }

  Closure<String> groupName = { BranchContext ctx -> sanitizeName("gocd-${ctx.branch}-${ctx.title}") }

  branches {
    matching {
      from = github {
        fullRepoName = 'gocd/gocd'
        apiAuthToken = lookup('github.auth.token')

        materialUrl = "https://git.gocd.io/git/${fullRepoName}"
      }

      onMatch { BranchContext ctx ->

        // post build status back to github
        ctx.repo.notifiesBy(ctx.provider)

        if (manualTrigger(ctx)) {
          pipeline("trigger-${ctx.branchSanitized}") {
            group = groupName(ctx)
            materials { add(ctx.repo) }
            stages {
              stage('trigger') {
                approval { type = 'manual' }
                jobs { job('do-nothing') {
                  elasticProfileId = 'ecs-dind-gocd-agent' // the tiniest profile we have
                  tasks { exec { commandLine = ['echo', "Triggering pull request [${ctx.identifier.replace('#', "##")}]: [${ctx.branch}] ${ctx.title}"] } } }
                }
              }
            }
          }
        }

        pipeline("build-linux-${ctx.branchSanitized}") {
          group = groupName(ctx)
          template = 'build-gradle-linux'
          materials {
            add(ctx.repo)
            if (manualTrigger(ctx)) {
              dependency('trigger') {
                pipeline = "trigger-${ctx.branchSanitized}"
                stage = 'trigger'
              }
            }
          }
        }

        if (includeWindows()) {
          pipeline("build-windows-${ctx.branchSanitized}") {
            group = groupName(ctx)
            template = 'build-gradle-windows'
            materials {
              add(ctx.repo)
              if (manualTrigger(ctx)) {
                dependency('trigger') {
                  pipeline = "trigger-${ctx.branchSanitized}"
                  stage = 'trigger'
                }
              }
            }
          }
        }

        pipeline("plugins-${ctx.branchSanitized}") {
          group = groupName(ctx)
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

            if (includeWindows()) {
              dependency('windows') {
                pipeline = "build-windows-${ctx.branchSanitized}"
                stage = 'build-server'
              }
            }
          }
        }

        pipeline("installers-${ctx.branchSanitized}") {
          group = groupName(ctx)
          template = 'installers-gradle'

          materials {
            add(ctx.repo)

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
          group = groupName(ctx)
          template = 'smoke-gradle'

          materials {
            dependency('installers') {
              pipeline = "installers-${ctx.branchSanitized}"
              stage = 'docker'
            }

            git('ruby-functional-tests') {
              url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
              destination = '#{selenium-working-dir}'
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
            'installers-pipeline-name': String.format("installers-%s", ctx.branchSanitized),
            'plugins-pipeline-name'   : String.format("plugins-%s/installers-%s", ctx.branchSanitized, ctx.branchSanitized),
            'sahi-working-dir'        : 'sahi-tests',
            'selenium-working-dir'    : 'selenium-tests',
          ]
        }

        pipeline("trigger-regressions-${ctx.branchSanitized}") {
          group = groupName(ctx)
          materials {
            dependency('smoke') {
              pipeline = "smoke-${ctx.branchSanitized}"
              stage = 'Smoke'
            }
          }

          stages {
            stage('trigger') {
              approval { type = 'manual' }
              jobs { job('do-nothing') {
                elasticProfileId = 'ecs-dind-gocd-agent' // the tiniest profile we have
                tasks { exec { commandLine = ['echo', "Triggering regressions on pull request [${ctx.identifier.replace('#', "##")}]: [${ctx.branch}] ${ctx.title}"] } } }
              }
            }
          }
        }

        pipeline("regression-SPAs-${ctx.branchSanitized}") {
          group = groupName(ctx)
          template = 'regression-ruby-webdriver'

          materials {
            dependency('smoke') {
              pipeline = "smoke-${ctx.branchSanitized}"
              stage = 'Smoke'
              ignoreForScheduling = true
            }

            dependency('trigger-regressions') {
              pipeline = "trigger-regressions-${ctx.branchSanitized}"
              stage = 'trigger'
            }

            git('ruby-functional-tests') {
              url = 'https://git.gocd.io/git/gocd/ruby-functional-tests'
              shallowClone = true
              blacklist = ['**/*']
            }
          }
          environmentVariables = [
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

  // gather all the pipelines generated to this point so we can add
  // them to the gocd environment
  pullRequestPipelineNames.addAll(pipelines.collect { p -> p.name })

  environments {
    environment('gocd') {
      pipelines = pullRequestPipelineNames // add all pipelines
    }
  }
}
