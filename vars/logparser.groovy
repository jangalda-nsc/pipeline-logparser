// ===================================
// = logparser for Jenkins pipelines =
// ===================================
// a library to parse and filter logs

// *******************
// * SCRIPT VARIABLE *
// *******************
@groovy.transform.Field
def verbose = false

@NonCPS
def setVerbose(v) {
    this.verbose = v
}

@groovy.transform.Field
def cachedTree = [:]

// **********************
// * INTERNAL FUNCTIONS *
// **********************
@NonCPS
org.jenkinsci.plugins.workflow.job.views.FlowGraphAction _getFlowGraphAction(build) {
    def flowGraph = build.rawBuild.allActions.findAll { it.class == org.jenkinsci.plugins.workflow.job.views.FlowGraphAction }
    assert flowGraph.size() == 1
    return flowGraph[0]
}

@NonCPS
org.jenkinsci.plugins.workflow.graph.FlowNode _getNode(flowGraph, id) {
    def node = flowGraph.nodes.findAll{ it.id == id }
    assert node.size() == 1
    node = node[0]
}

@NonCPS
org.jenkinsci.plugins.workflow.actions.LogAction _getLogAction(node) {
    def logaction = \
        node.actions.findAll {
            it.class.name == 'org.jenkinsci.plugins.workflow.support.actions.LogActionImpl' ||
            it.class.name == 'org.jenkinsci.plugins.workflow.support.actions.LogStorageAction'
        }
    assert logaction.size() == 1 || logaction.size() == 0

    if (logaction.size() == 0) {
        return null
    }
    return logaction[0]
}

@NonCPS
java.util.LinkedHashMap _getNodeTree(build, _flowGraph = null, _node = null, _branches=[], _stages=[]) {
    def key=build.getFullDisplayName()
    if (this.cachedTree.containsKey(key) == false) {
        this.cachedTree[key] = [:]
    }

    def flowGraph = _flowGraph
    if (flowGraph == null) {
        flowGraph = _getFlowGraphAction(build)
    }
    def node = _node
    def name = null
    def stage = false
    def branches = _branches.collect{ it }
    def stages = _stages.collect { it }
    def label = null

    if (node == null || this.cachedTree[key].containsKey(node.id) == false || this.cachedTree[key][node.id].active) {
        // fill in branches and stages lists for children (root branch + named branches/stages only)
        if (node == null) {
            if (flowGraph.nodes.size() == 0) {
                // pipeline not yet started, or failed before start
                return [:]
            }
            def rootNode = flowGraph.nodes.findAll{ it.enclosingId == null && it.class == org.jenkinsci.plugins.workflow.graph.FlowStartNode }
            assert rootNode.size() == 1
            node = rootNode[0]
            branches += [ node.id ]
        } else if (node.class == org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode) {
            if (node.descriptor instanceof org.jenkinsci.plugins.workflow.cps.steps.ParallelStep$DescriptorImpl) {
                def labelAction = node.actions.findAll { it.class == org.jenkinsci.plugins.workflow.cps.steps.ParallelStepExecution$ParallelLabelAction }
                assert labelAction.size() == 1 || labelAction.size() == 0
                if (labelAction.size() == 1) {
                    name = labelAction[0].threadName
                    branches.add(0, node.id)
                }
            } else if (node.descriptor instanceof org.jenkinsci.plugins.workflow.support.steps.StageStep$DescriptorImpl) {
                def labelAction = node.actions.findAll { it.class == org.jenkinsci.plugins.workflow.actions.LabelAction }
                assert labelAction.size() == 1 || labelAction.size() == 0
                if (labelAction.size() == 1) {
                    name = labelAction[0].displayName
                    stage = true
                    branches.add(0, node.id)
                    stages.add(0, node.id)
                }
            } else if (node.descriptor instanceof org.jenkinsci.plugins.workflow.support.steps.ExecutorStep$DescriptorImpl && node.displayName=='Allocate node : Start') {
                def argAction = node.actions.findAll { it.class == org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl }
                assert argAction.size() == 1
                if (argAction[0].unmodifiedArguments) {
                    label=argAction[0].argumentsInternal.label
                }
                else {
                    // the label was filtered: record the hostname then
                    def wsAction = node.actions.findAll { it.class == org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl }
                    assert wsAction.size() == 1
                    label=wsAction[0].node
                }
            }
        }

        // add node information in tree
        // get active state first
        def active = node.isActive() == true
        // get children AFTER active state (avoid incomplete list if state was still active)
        def children = flowGraph.nodes.findAll{ it.enclosingId == node.id }.sort{ Integer.parseInt("${it.id}") }
        def logaction = _getLogAction(node)

        // add parent in tree first
        if (this.cachedTree[key].containsKey(node.id) == false) {
            this.cachedTree[key][node.id] = [ \
                id: node.id,
                name: name,
                stage: stage,
                parents: node.allEnclosingIds,
                parent: node.enclosingId,
                children: children.collect{ it.id },
                branches: _branches,
                stages: _stages,
                active: active,
                haslog: logaction != null,
                displayFunctionName: node.displayFunctionName,
                url: node.url,
                label: label
            ]
        } else {
            // node exist in cached tree but was active last time it was updated: refresh its children and status
            this.cachedTree[key][node.id].active = active
            this.cachedTree[key][node.id].children = children.collect{ it.id }
            this.cachedTree[key][node.id].haslog = logaction != null
        }
        // then add children
        children.each{
            _getNodeTree(build, flowGraph, it, branches, stages)
        }
    }
    // else : node was already put in tree while inactive, nothing to update

    return this.cachedTree[key]
}


@NonCPS
java.util.ArrayList _getBranches(java.util.LinkedHashMap tree, id, Boolean showStages, Boolean mergeNestedDuplicates) {
    def branches = tree[id].branches.collect{ it }
    if (showStages == false) {
        branches = branches.minus(tree[id].stages)
    }
    branches = branches.collect {
        def item = tree[it]
        assert item != null
        assert item.name != null || item.parent == null
        return item.name
    }.findAll { it != null }

    if (tree[id].name != null) {
        if (tree[id].stage == false || showStages == true) {
            branches.add(0, tree[id].name)
        }
    }

    // remove consecutive duplicates
    // this would happen if 2 nested parallel steps or stages have the same name
    // which is exactly what happens in declarative matrix (parallel step xxx contain stage xxx)
    if (mergeNestedDuplicates) {
        def i = 0
        branches = branches.findAll {
            i++ == 0 || branches[i - 2] != it
        }
    }
    return branches
}

//*******************************
//* GENERATE URL TO BRANCH LOGS *
//*******************************

// add trailing / and remove any //
@NonCPS
String _cleanRootUrl(String urlIn) {
    def urlOut = urlIn + '/'
    urlOut = urlOut.replaceAll(/([^:])\/\/+/, '$1/')
    return urlOut
}

@NonCPS
java.util.ArrayList getBlueOceanUrls(build = currentBuild) {
    // if JENKIN_URL not configured correctly, use placeholder
    def jenkinsUrl = _cleanRootUrl(env.JENKINS_URL ?: '$JENKINS_URL')

    def rootUrl = null
    build.rawBuild.allActions.findAll { it.class == io.jenkins.blueocean.service.embedded.BlueOceanUrlAction }.each {
        rootUrl = _cleanRootUrl(jenkinsUrl + it.blueOceanUrlObject.url)
    }
    assert rootUrl != null

    // TODO : find a better way to do get the rest url for this build ...
    def blueProvider = new io.jenkins.blueocean.service.embedded.BlueOceanRootAction.BlueOceanUIProviderImpl()
    def buildenv = build.rawBuild.getEnvironment()
    def restUrl = _cleanRootUrl("${jenkinsUrl}${blueProvider.getUrlBasePrefix()}/rest${blueProvider.getLandingPagePath()}${buildenv.JOB_NAME.replace('/','/pipelines/')}/runs/${buildenv.BUILD_NUMBER}")

    def tree = _getNodeTree(build)
    def ret = []

    if (this.verbose) {
        print "rootUrl=${rootUrl}"
        print "restUrl=${restUrl}"
        print "tree=${tree}"
    }

    tree.values().findAll{ it.parent == null || it.name != null }.each {
        def url = "${rootUrl}pipeline/${it.id}"
        def log = "${restUrl}nodes/${it.id}/log/?start=0"
        if (it.parent == null) {
            url = "${rootUrl}pipeline"
            log = "${restUrl}log/?start=0"
        }
        // if more than one stage blue ocean urls are invalid
        def parent = it.branches.size() > 0 ? it.branches[0] : null
        ret += [ [ id: it.id, name: it.name, stage: it.stage, parents: it.branches, parent: parent, url: url, log: log ] ]
    }

    if (this.verbose) {
        print "BlueOceanUrls=${ret}"
    }
    return ret
}

@NonCPS
java.util.ArrayList getPipelineStepsUrls(build = currentBuild) {
    def tree = _getNodeTree(build)
    def ret = []

    if (this.verbose) {
        print "tree=${tree}"
    }

    // if JENKIN_URL not configured correctly, use placeholder
    def jenkinsUrl = _cleanRootUrl(env.JENKINS_URL ?: '$JENKINS_URL')

    tree.values().each {
        def url = _cleanRootUrl("${jenkinsUrl}${it.url}")
        def log = null
        if (it.haslog) {
            log = "${url}log"
        }
        ret += [ [ id: it.id, name: it.name, stage: it.stage, parents: it.parents, parent: it.parent, children: it.children, url: url, log: log, label: it.label ] ]
    }

    if (this.verbose) {
        print "PipelineStepsUrls=${ret}"
    }
    return ret
}

//***************************
//* LOG FILTERING & EDITING *
//***************************

@NonCPS
java.util.LinkedHashMap _parseOptions(java.util.LinkedHashMap options = [:])
{
    def defaultOptions = [ filter: [], showParents: true, showStages: true, markNestedFiltered: true, hidePipeline: true, hideVT100: true, mergeNestedDuplicates: true ]
    // merge 2 maps with priority to options values
    def new_options = defaultOptions.plus(options)
    new_options.keySet().each{ assert it in ['filter', 'showParents', 'showStages', 'markNestedFiltered', 'mergeNestedDuplicates', 'hidePipeline', 'hideVT100'], "invalid option $it" }
    return new_options
}

@NonCPS
Boolean _keepBranches(java.util.ArrayList branches, java.util.ArrayList filter) {
    return \
        filter.size() == 0 ||
        (branches.size() == 0 && null in filter) ||
        (branches.size() > 0 && filter.count{ it != null && it in CharSequence && branches[0] ==~ /^${it.toString()}$/ } > 0) ||
        (branches.size() > 0 && filter.count{
            if (it != null && it in Collection && branches.size() >= it.size()) {
                def index = 0
                it.count { pattern ->
                    branches[it.size() - index++ - 1] ==~ /^${pattern.toString()}$/
                } == it.size()
            }
        } > 0)
}

// return log file with BranchInformation
// - return logs only for one branch if filterBranchName not null (default null)
// - with parent information for nested branches if options.showParents is true (default)
//   example:
//      if true: "[branch2] [branch21] log from branch21 nested in branch2"
//      if false "[branch21] log from branch21 nested in branch2"
// - with a marker showing nested branches if options.markNestedFiltered is true (default) and if filterBranchName is not null
//   example:
//      "<nested branch [branch2] [branch21]"
// - without VT100 markups if options.hideVT100 is true (default)
// - without Pipeline technical logs if options.hidePipeline is true (default)
// - with stage information if showStage is true (default true)
// - with duplicate branch names removed if mergeNestedDuplicates is true (default true)
//
// cf https://stackoverflow.com/questions/38304403/jenkins-pipeline-how-to-get-logs-from-parallel-builds
// cf https://stackoverflow.com/a/57351397
// (workaround for https://issues.jenkins-ci.org/browse/JENKINS-54304)
@NonCPS
String getLogsWithBranchInfo(java.util.LinkedHashMap options = [:], build = currentBuild)
{
    // return value
    def output = ''

    // 1/ parse options
    def opt = _parseOptions(options)

    /* TODO: option to show logs before start of pipeline
    if (opt.filter.size() == 0 || null in opt.filter) {
        def b = new ByteArrayOutputStream()
        def s = new StreamTaskListener(b, Charset.forName('UTF-8'))
        build.rawBuild.allActions.findAll{ it.class == hudson.model.CauseAction }.each{ it.causes.each { it.print(s) } }
        if (opt.hideVT100) {
            output += b.toString().replaceAll(/\x1B\[8m.*?\x1B\[0m/, '')
        } else {
            output += b.toString()
        }
    }
    */

    def flowGraph = _getFlowGraphAction(build)
    def tree = _getNodeTree(build, flowGraph)

    if (this.verbose) {
        print "tree=${tree}"
    }

    def keep = [:]

    tree.values().each {
        def branches = _getBranches(tree, it.id, opt.showStages, opt.mergeNestedDuplicates)

        keep."${it.id}" = _keepBranches(branches, opt.filter)

        if (keep."${it.id}") {
            // no filtering or kept branch: keep logs

            def prefix = ''
            if (branches.size() > 0) {
                if (opt.showParents) {
                     prefix = branches.reverse().collect{ "[$it] " }.sum()
                } else {
                     prefix = "[${branches[0]}] "
                }
            }

            if (opt.hidePipeline == false) {
                output += "[Pipeline] ${prefix}${it.displayFunctionName}\n"
            }

            if (it.haslog) {
                def node = _getNode(flowGraph, it.id)
                def logaction = _getLogAction(node)
                assert logaction != null

                ByteArrayOutputStream b = new ByteArrayOutputStream()
                if (opt.hideVT100) {
                    logaction.logText.writeLogTo(0, b)
                } else {
                    logaction.logText.writeRawLogTo(0, b)
                }
                if (prefix != '') {
                    def str = b.toString()
                    // split(regex,limit) with negative limit in case of trailing \n\n (0 or positive limit would strip trailing \n and limit the list size)
                    def logList = str.split('\n', -1).collect{ "${prefix}${it}" }
                    if (str.endsWith('\n')) {
                        logList.remove(logList.size() - 1)
                        output += logList.join('\n') + '\n'
                    } else if (str.size() > 0) {
                        output += logList.join('\n')
                    }
                } else {
                     output += b.toString()
                }
            }
        } else if (opt.markNestedFiltered && it.name != null && it.parents.findAll { keep."${it}" }.size() > 0) {
            def showNestedMarker = true
            if (opt.mergeNestedDuplicates) {
                def branchesWithDuplicates = _getBranches(tree, it.id, opt.showStages, false)
                if (branchesWithDuplicates.size() > 1 && branchesWithDuplicates[1] == it.name) {
                    // this is already a duplicate branch merged into its parent : marker was already put for parent branch
                    showNestedMarker = false
                }
            }
            if (showNestedMarker) {
                // branch is not kept (not in filter) but one of its parent branch is kept: record it as filtered
                def prefix = null
                if (opt.showParents) {
                    prefix = branches.reverse().collect{ "[$it]" }.join(' ')
                } else {
                    prefix = "[${branches[0]}]"
                }
                output += "<nested branch ${prefix}>\n"
            }
        }
        // else none of the parent branches is kept, skip this one entirely
    }
    return output
}

// get list of branches and parents
// (list of list one item per branch, parents first, null if no branch name)
// each item of the list can be used as filter
@NonCPS
java.util.ArrayList getBranches(java.util.LinkedHashMap options = [:], build = currentBuild)
{
    // return value
    def result = []

    // 1/ parse options
    def opt = _parseOptions(options)

    def flowGraph = _getFlowGraphAction(build)
    def tree = _getNodeTree(build, flowGraph)

    if (this.verbose) {
        print "tree=${tree}"
    }

    tree.values().each {
        def branches = _getBranches(tree, it.id, opt.showStages, opt.mergeNestedDuplicates)

        if (branches.size() == 0) {
            // no branch is represented as [null] when filtering
            // to distinguish from [] : all branches
            branches = [ null ]
        }

        def keep = _keepBranches(branches, opt.filter)

        // reverse order to match filtering order (parents first)
        branches = branches.reverse()

        if (keep && ! (branches in result) ) {
            result += [ branches ]
        }
    }
    return result
}

//*************
//* ARCHIVING *
//*************

// archive buffer directly on the master (no need to instantiate a node like ArchiveArtifact)
// cf https://github.com/gdemengin/pipeline-whitelist
@NonCPS
void archiveArtifactBuffer(String name, String buffer) {
    def jobRoot = currentBuild.rawBuild.getRootDir()
    def file = new File("${jobRoot}/archive/${name}")

    if (this.verbose) {
        print "logparser: archiving ${file.path}"
    }

    if (! file.parentFile.exists()){
        file.parentFile.mkdirs();
    }
    file.write(buffer)
}

// archive logs with [<branch>] prefix on lines belonging to <branch>
// and filter by branch if filterBranchName not null
// cf https://stackoverflow.com/questions/38304403/jenkins-pipeline-how-to-get-logs-from-parallel-builds
// cf https://stackoverflow.com/a/57351397
// (workaround for https://issues.jenkins-ci.org/browse/JENKINS-54304)
@NonCPS
void archiveLogsWithBranchInfo(String name, java.util.LinkedHashMap options = [:])
{
    archiveArtifactBuffer(name, getLogsWithBranchInfo(options))
}


return this
