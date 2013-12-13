package rundeck.controllers

import com.dtolabs.client.utils.Constants
import com.dtolabs.rundeck.app.api.ApiBulkJobDeleteRequest
import com.dtolabs.rundeck.core.authentication.Group
import com.dtolabs.rundeck.core.common.Framework

import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.utils.NodeSet
import com.dtolabs.rundeck.server.authorization.AuthConstants
import grails.converters.JSON
import groovy.xml.MarkupBuilder
import org.apache.commons.collections.list.TreeList
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.UsernamePasswordCredentials
import org.apache.commons.httpclient.auth.AuthScope
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.params.HttpClientParams
import org.apache.commons.httpclient.util.DateParseException
import org.apache.commons.httpclient.util.DateUtil
import org.apache.log4j.Logger
import org.apache.log4j.MDC
import org.codehaus.groovy.grails.web.json.JSONElement
import org.quartz.CronExpression
import org.quartz.Scheduler
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.multipart.commons.CommonsMultipartFile
import rundeck.CommandExec
import rundeck.Execution
import rundeck.Option
import rundeck.ScheduledExecution
import rundeck.User
import rundeck.Workflow
import rundeck.codecs.JobsXMLCodec
import rundeck.codecs.JobsYAMLCodec
import rundeck.filters.ApiRequestFilters
import rundeck.services.ApiService
import rundeck.services.ExecutionService
import rundeck.services.ExecutionServiceException
import rundeck.services.FrameworkService
import rundeck.services.NotificationService
import rundeck.services.ScheduledExecutionService

import javax.servlet.http.HttpServletResponse
import java.util.regex.Pattern
import javax.security.auth.Subject

class ScheduledExecutionController  {
    def Scheduler quartzScheduler
    def ExecutionService executionService
    def FrameworkService frameworkService
    def ScheduledExecutionService scheduledExecutionService
    def NotificationService notificationService
    def ApiService apiService

 
    def index = { redirect(controller:'menu',action:'jobs',params:params) }

    // the delete, save and update actions only
    // accept POST requests
    def static allowedMethods = [delete:'POST',
        save:'POST',
        update:'POST',
        deleteBulk:'POST',
        apiJobsImport:'POST',
        apiJobDelete:'DELETE',
        apiJobAction:['GET','DELETE'],
        apiRunScript:'POST',
        apiJobDeleteBulk:['DELETE','POST'],
        apiJobClusterTakeoverSchedule:'PUT',
        apiJobUpdateSingle:'PUT'
    ]

    def cancel = {
        //clear session workflow data
        if(session.editWF ){
            session.removeAttribute('editWF');
            session.removeAttribute('undoWF');
            session.removeAttribute('redoWF');
        }
        if(session.editWF ){
            session.removeAttribute('editOPTS');
            session.removeAttribute('undoOPTS');
            session.removeAttribute('redoOPTS');
        }
        if(params.id && params.id!=''){
            redirect(action:show,params:[id:params.id])
        }else{
            redirect(action:index)
        }
    }
    def list = {redirect(action:index,params:params) }

    def groupTreeFragment = {
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        def tree = scheduledExecutionService.getGroupTree(params.project,framework)
        render(template:"/menu/groupTree",model:[jobgroups:tree,jscallback:params.jscallback])
    }

    private void error(){
        withFormat{
            html{
                return render(view:"/common/error")
            }
            xml {
                return xmlerror()
            }
        }
    }
    def xmlerror={
        render(contentType:"text/xml",encoding:"UTF-8"){
            result(error:"true"){
                delegate.'error'{
                    if(flash.error){
                        response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,flash.error)
                        delegate.'message'(flash.error)
                    }
                    if(flash.errors){
                        def p = delegate
                        flash.errors.each{ msg ->
                            p.'message'(msg)
                        }
                    }
                }
            }
        }
    }
    def xmlsuccess={
        render(contentType:"text/xml",encoding:"UTF-8"){
            delegate.'result'(error:"false"){
                delegate.'success'{
                    if(flash.message){
                        response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,flash.message)
                        delegate.'message'(flash.message)
                    }
                    if(flash.messages){
                        def p = delegate
                        flash.messages.each{ msg ->
                            p.'message'(msg)
                        }
                    }
                }
            }
        }
    }
    def detailFragment = {
        log.debug("ScheduledExecutionController: show : params: " + params)
        def crontab = [:]
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if (!scheduledExecution) {
            log.error("No Job found for id: " + params.id)
            flash.error="No Job found for id: " + params.id
            response.setStatus (404)
            return error()
        }
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_READ], scheduledExecution.project)) {
            return unauthorized("Read Job ${params.id}")
        }
        crontab = scheduledExecution.timeAndDateAsBooleanMap()
        //list executions using query params and pagination params

        def executions=Execution.findAllByScheduledExecution(scheduledExecution,[offset: params.offset?params.offset:0, max: params.max?params.max:10, sort:'dateStarted', order:'desc'])

        def total = Execution.countByScheduledExecution(scheduledExecution)

        def remoteClusterNodeUUID = null
        if (scheduledExecution.scheduled && frameworkService.isClusterModeEnabled()
                && scheduledExecution.serverNodeUUID != frameworkService.getServerUUID()) {
            remoteClusterNodeUUID = scheduledExecution.serverNodeUUID
        }

        return render(view:'jobDetailFragment',model: [scheduledExecution:scheduledExecution, crontab:crontab, params:params,
            executions:executions,
            total:total,
            nextExecution:scheduledExecutionService.nextExecutionTime(scheduledExecution),
                remoteClusterNodeUUID: remoteClusterNodeUUID,
            max: params.max?params.max:10,
                notificationPlugins: notificationService.listNotificationPlugins(),
            offset:params.offset?params.offset:0])
    }
    def show = {
        log.debug("ScheduledExecutionController: show : params: " + params)
        def crontab = [:]
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if (!scheduledExecution) {
            log.error("No Job found for id: " + params.id)
            flash.error="No Job found for id: " + params.id
            response.setStatus (404)

            return render(view: "/common/error")
        }
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_READ], scheduledExecution.project)) {
            return unauthorized("Read Job ${params.id}")
        }
        crontab = scheduledExecution.timeAndDateAsBooleanMap()
        def User user = User.findByLogin(session.user)
        //list executions using query params and pagination params

        def executions=Execution.findAllByScheduledExecution(scheduledExecution,[offset: params.offset?params.offset:0, max: params.max?params.max:10, sort:'dateStarted', order:'desc'])

        def total = Execution.countByScheduledExecution(scheduledExecution)

        def remoteClusterNodeUUID=null
        if (scheduledExecution.scheduled && frameworkService.isClusterModeEnabled()
                && scheduledExecution.serverNodeUUID != frameworkService.getServerUUID()) {
            remoteClusterNodeUUID = scheduledExecution.serverNodeUUID
        }
        def dataMap= [scheduledExecution: scheduledExecution, crontab: crontab, params: params,
                executions: executions,
                total: total,
                nextExecution: scheduledExecutionService.nextExecutionTime(scheduledExecution),
                remoteClusterNodeUUID: remoteClusterNodeUUID,
                notificationPlugins: notificationService.listNotificationPlugins(),
                max: params.max ? params.max : 10,
                offset: params.offset ? params.offset : 0] + _prepareExecute(scheduledExecution, framework)
        withFormat{
            html{
                dataMap
            }
            yaml{
                render(text:JobsYAMLCodec.encode([scheduledExecution] as List),contentType:"text/yaml",encoding:"UTF-8")
            }

            xml{
                def fname=scheduledExecution.jobName.replaceAll(' ','_')
                fname=fname.replaceAll('"','_')
                fname=fname.replaceAll('\\\\','_')
                final Pattern s = Pattern.compile("[\\r\\n]")
                fname=fname.replaceAll(s,'_')
                if(fname.size()>74){
                    fname = fname.substring(0,74)
                }
                response.setHeader("Content-Disposition","attachment; filename=\"${fname}.xml\"")
                response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,"Jobs found: 1")

                def writer = new StringWriter()
                def xml = new MarkupBuilder(writer)
                JobsXMLCodec.encodeWithBuilder([scheduledExecution],xml)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
        }
        dataMap
    }


    /**
     * check crontabString parameter if it is a valid crontab, and render any syntax warnings
     */
    def checkCrontab={
        if(!params.crontabString){
            request.error="crontabString parameter is required"
        }else{
            if(!CronExpression.isValidExpression(params.crontabString)){
                def x = params.crontabString.split(" ")
                if(x && x.size()>6 && x [3] != '?' && x [5]!='?'){
                    request.warn="day of week or day of month must be '?'"
                }else{
                    request.warn="Format invalid"
                }
            }
        }
        render(template:'/common/messages')
    }

    /**
     * This action loads the JSON data from the URL specified in
     * an option's "valueSrc" property, and renders the optionValuesSelect template
     * using the data.
     */
    def loadRemoteOptionValues={
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if (!scheduledExecution) {
            log.error("No Job found for id: " + params.id)
            flash.error="No Job found for id: " + params.id
            response.setStatus (404)
            return error()
        }
        if(!params.option){
            log.error("option missing")
            flash.error="option missing"
            response.setStatus (404)
            return error()
        }
        
        //see if option specified, and has url
        if (scheduledExecution.options && scheduledExecution.options.find {it.name == params.option}) {
            def Option opt = scheduledExecution.options.find {it.name == params.option}
            def values=[]
            if (opt.realValuesUrl) {
                //load expand variables in URL source
                String srcUrl = expandUrl(opt, opt.realValuesUrl.toExternalForm(), scheduledExecution,params.extra?.option)
                String cleanUrl=srcUrl.replaceAll("^(https?://)([^:@/]+):[^@/]*@",'$1$2:****@');
                def remoteResult=[:]
                def result=null
                def remoteStats=[startTime: System.currentTimeMillis(), httpStatusCode: "", httpStatusText: "", contentLength: "", url: srcUrl,durationTime:"",finishTime:"", lastModifiedDateTime:""]
                def err = [:]
                try {
                    remoteResult = getRemoteJSON(srcUrl, 10)
                    result=remoteResult.json
                    if(remoteResult.stats){
                        remoteStats.putAll(remoteResult.stats)
                    }
                } catch (Exception e) {
                    err.message = "Failed loading remote option values"
                    err.exception = e
                    err.srcUrl = cleanUrl
                    log.error("getRemoteJSON error: URL ${cleanUrl} : ${e.message}");
                    e.printStackTrace()
                    remoteStats.finishTime=System.currentTimeMillis()
                    remoteStats.durationTime= remoteStats.finishTime- remoteStats.startTime
                }
                if(remoteResult.error){
                    err.message = "Failed loading remote option values"
                    err.exception = new Exception(remoteResult.error)
                    err.srcUrl = cleanUrl
                    log.error("getRemoteJSON error: URL ${cleanUrl} : ${remoteResult.error}");
                }
                logRemoteOptionStats(remoteStats,[jobName:scheduledExecution.generateFullName(),id:scheduledExecution.extid, jobProject:scheduledExecution.project,optionName:params.option,user:session.user])
                //validate result contents
                boolean valid = true;
                def validationerrors=[]
                if(result){
                    if( result instanceof Collection){
                        result.eachWithIndex { entry,i->
                            if(entry instanceof org.codehaus.groovy.grails.web.json.JSONObject){
                                if(!entry.name){
                                    validationerrors<<"Item: ${i} has no 'name' entry"
                                    valid=false;
                                }
                                if(!entry.value){
                                    validationerrors<<"Item: ${i} has no 'value' entry"
                                    valid = false;
                                }
                            }else if(!(entry instanceof String)){
                                valid = false;
                                validationerrors << "Item: ${i} expected string or map like {name:\"..\",value:\"..\"}"
                            }
                        }
                    } else if (result instanceof org.codehaus.groovy.grails.web.json.JSONObject) {
                        org.codehaus.groovy.grails.web.json.JSONObject jobject = result
                        result = []
                        jobject.keys().sort().each {k ->
                            result << [name: k, value: jobject.get(k)]
                        }
                    }else{
                        validationerrors << "Expected top-level list with format: [{name:\"..\",value:\"..\"},..], or ['value','value2',..] or simple object with {name:\"value\",...}"
                        valid=false
                    }
                    if(!valid){
                        result=null
                        err.message="Failed parsing remote option values: ${validationerrors.join('\n')}"
                    }
                }else if(!err){
                    err.message = "Empty result"
                    err.code='empty'
                }
                def model= [optionSelect: opt, values: result, srcUrl: cleanUrl, err: err, fieldPrefix: params.fieldPrefix, selectedvalue: params.selectedvalue]
                if(params.extra?.option?.get(opt.name)){
                    model.selectedoptsmap=[(opt.name):params.extra.option.get(opt.name)]
                }
                return render(template: "/framework/optionValuesSelect", model: model);
            } else {
                return error()
            }
        }else{
            return error()
        }

    }
    static Logger optionsLogger = Logger.getLogger("com.dtolabs.rundeck.remoteservice.http.options")
    private logRemoteOptionStats(stats,jobdata){
        stats.keySet().each{k->
            def v= stats[k]
            if(v instanceof Date){
                //TODO: reformat date
                MDC.put(k,v.toString())
                MDC.put("${k}Time",v.time.toString())
            }else if(v instanceof String){
                MDC.put(k,v?v:"-")
            }else{
                final string = v.toString()
                MDC.put(k, string?string:"-")
            }
        }
        jobdata.keySet().each{k->
            final var = jobdata[k]
            MDC.put(k,var?var:'-')
        }
        optionsLogger.info(stats.httpStatusCode + " " + stats.httpStatusText+" "+stats.contentLength+" "+stats.url)
        stats.keySet().each {k ->
            if (stats[k] instanceof Date) {
                //reformat date
                MDC.remove(k+'Time')
            }
            MDC.remove(k)
        }
        jobdata.keySet().each {k ->
            MDC.remove(k)
        }
    }

    /**
     * Map of descriptive property name to ScheduledExecution domain class property names
     * used by expandUrl for embedded property references in remote options URL
     */
    private static jobprops=[
        name:'jobName',
        group:'groupPath',
        description:'description',
        project:'project',
        argString:'argString',
        adhoc:'adhocExecution'
    ]
    /**
     * Map of descriptive property name to Option domain class property names
     * used by expandUrl for embedded property references in remote options URL
     */
    private static optprops=[
        name:'name',

    ]
    /**
     * Expand the URL string's embedded property references of the form
     * ${job.PROPERTY} and ${option.PROPERTY}.  available properties are
     * limited
     */
    protected String expandUrl(Option opt, String url, ScheduledExecution scheduledExecution,selectedoptsmap=[:]) {
        def invalid = []
        String srcUrl = url.replaceAll(/(\$\{(job|option)\.([^}]+?(\.value)?)\})/,
            {Object[] group ->
                if(group[2]=='job' && jobprops[group[3]] && scheduledExecution.properties.containsKey(jobprops[group[3]])) {
                    scheduledExecution.properties.get(jobprops[group[3]]).toString().encodeAsURL()
                }else if(group[2]=='job' && group[3]=='user.name' ) {
                    def amlSessUser = (session?.user) ? session.user : "anonymous"
                    amlSessUser.toString().encodeAsURL()
                }else if(group[2]=='option' && optprops[group[3]] && opt.properties.containsKey(optprops[group[3]])) {
                    opt.properties.get(optprops[group[3]]).toString().encodeAsURL()
                }else if(group[2]=='option' && group[4]=='.value' ) {
                    def optname= group[3].substring(0, group[3].length() - '.value'.length())
                    def value=selectedoptsmap&& selectedoptsmap instanceof Map?selectedoptsmap[optname]:null
                    //find option with name
                    def Option expopt = scheduledExecution.options.find {it.name == optname}
                    if(value && expopt.multivalued && (value instanceof Collection || value instanceof String[])){
                        value = value.join(expopt.delimiter)
                    }
                    value?:''
                } else {
                    invalid << group[0]
                    group[0]
                }
            }
        )
        if (invalid) {
            log.error("invalid expansion: " + invalid);
        }
        return srcUrl
    }

    /**
     * Make a remote URL request and return the parsed JSON data and statistics for http requests in a map.
     * if an error occurs, a map with a single 'error' entry will be returned.
     * the stats data contains:
     *
     * url: requested url
     * startTime: start time epoch ms
     * httpStatusCode: http status code (int)
     * httpStatusText: http status text
     * finishTime: finish time epoch ms
     * durationTime: duration time in ms
     * contentLength: response content length bytes (long)
     * lastModifiedDate: Last-Modified header (Date)
     * contentSHA1: SHA1 hash of the content
     *
     * @param url URL to request
     * @param timeout request timeout in seconds
     * @return Map of data, [json: parsed json or null, stats: stats data, error: error message]
     *
     */
    private Object getRemoteJSON(String url, int timeout){
        //attempt to get the URL JSON data
        def stats=[:]
        if(url.startsWith("http:") || url.startsWith("https:")){
            final HttpClientParams params = new HttpClientParams()
            params.setConnectionManagerTimeout(timeout*1000)
            params.setSoTimeout(timeout*1000)
            def HttpClient client= new HttpClient(params)
            def URL urlo
            def AuthScope authscope=null
            def UsernamePasswordCredentials cred=null
            boolean doauth=false
            String cleanUrl = url.replaceAll("^(https?://)([^:@/]+):[^@/]*@", '$1$2:****@');
            try{
                urlo = new URL(url)
                if(urlo.userInfo){
                    doauth = true
                    authscope = new AuthScope(urlo.host,urlo.port>0? urlo.port:urlo.defaultPort,AuthScope.ANY_REALM,"BASIC")
                    cred = new UsernamePasswordCredentials(urlo.userInfo)
                    url = new URL(urlo.protocol, urlo.host, urlo.port, urlo.file).toExternalForm()
                }
            }catch(MalformedURLException e){
                throw new Exception("Failed to configure base URL for authentication: "+e.getMessage(),e)
            }
            if(doauth){
                client.getParams().setAuthenticationPreemptive(true);
                client.getState().setCredentials(authscope,cred)
            }
            def HttpMethod method = new GetMethod(url)
            method.setFollowRedirects(true)
            method.setRequestHeader("Accept","application/json")
            stats.url = cleanUrl;
            stats.startTime = System.currentTimeMillis();
            def resultCode = client.executeMethod(method);
            stats.httpStatusCode = resultCode
            stats.httpStatusText = method.getStatusText()
            stats.finishTime = System.currentTimeMillis()
            stats.durationTime=stats.finishTime-stats.startTime
            stats.contentLength = method.getResponseContentLength()
            final header = method.getResponseHeader("Last-Modified")
            if(null!=header){
                try {
                    stats.lastModifiedDate= DateUtil.parseDate(header.getValue())
                } catch (DateParseException e) {
                }
            }else{
                stats.lastModifiedDate=""
                stats.lastModifiedDateTime=""
            }
            try{
                def reasonCode = method.getStatusText();
                if(resultCode>=200 && resultCode<=300){
                    def expectedContentType="application/json"
                    def resultType=''
                    if (null != method.getResponseHeader("Content-Type")) {
                        resultType = method.getResponseHeader("Content-Type").getValue();
                    }
                    String type = resultType;
                    if (type.indexOf(";") > 0) {
                        type = type.substring(0, type.indexOf(";")).trim();
                    }

                    if (expectedContentType.equals(type)) {
                        final stream = method.getResponseBodyAsStream()
                        final writer = new StringWriter()
                        int len=copyToWriter(new BufferedReader(new InputStreamReader(stream, method.getResponseCharSet())),writer)
                        stream.close()
                        writer.flush()
                        final string = writer.toString()
                        def json=grails.converters.JSON.parse(string)
                        if(string){
                            stats.contentSHA1=string.encodeAsSHA1()
                            if(stats.contentLength<0){
                                stats.contentLength= len
                            }
                        }else{
                            stats.contentSHA1=""
                        }
                        return [json:json,stats:stats]
                    }else{
                        return [error:"Unexpected content type received: "+resultType,stats:stats]
                    }
                }else{
                    stats.contentSHA1 = ""
                    return [error:"Server returned an error response: ${resultCode} ${reasonCode}",stats:stats]
                }
            } finally {
                method.releaseConnection();
            }
        }else if (url.startsWith("file:")) {
            stats.url=url
            def File srfile = new File(new URI(url))
            final writer = new StringWriter()
            final stream= new FileInputStream(srfile)

            stats.startTime = System.currentTimeMillis();
            int len = copyToWriter(new BufferedReader(new InputStreamReader(stream)), writer)
            stats.finishTime = System.currentTimeMillis()
            stats.durationTime = stats.finishTime - stats.startTime
            stream.close()
            writer.flush()
            final string = writer.toString()
            final JSONElement parse = grails.converters.JSON.parse(string)
            if (string) {
                stats.contentSHA1 = string.encodeAsSHA1()
            }else{
                stats.contentSHA1 = ""
            }
            stats.contentLength=srfile.length()
            stats.lastModifiedDate=new Date(srfile.lastModified())
            stats.lastModifiedDateTime=srfile.lastModified()
            return [json:parse,stats:stats]
        } else {
            throw new Exception("Unsupported protocol: " + url)
        }
    }

    static int copyToWriter(Reader read, Writer writer){
        char[] chars = new char[1024];
        int len=0;
        int size=read.read(chars,0,chars.length)
        while(-1!=size){
            len+=size;
            writer.write(chars,0,size)
            size = read.read(chars, 0, chars.length)
        }
        return len;
    }

    /**
    */
    def delete = {
        if (!params.id) {
            request.error = g.message(code: 'api.error.parameter.required',args:['id'])
            response.setStatus(400)
            return error()
        }
        def jobid=params.id
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        def result = scheduledExecutionService.deleteScheduledExecutionById(jobid, framework, session.user, 'delete')
        if (!result.success) {
            request.error = result.error.message
            return error()
        } else {
            flash.bulkDeleteResult = [success: [result.success]]
            redirect(controller: 'menu', action: 'jobs')
        }
    }
    /**
     * Delete a set of jobs as specified in the idlist parameter.
     * Only allowed via POST http method
     */
    def deleteBulk = {ApiBulkJobDeleteRequest deleteRequest ->
        log.debug("ScheduledExecutionController: deleteBulk : params: " + params)
        if (!params.ids && !params.idlist) {
            flash.error = g.message(code: 'ScheduledExecutionController.bulkDelete.empty')
            return redirect(controller: 'menu', action: 'jobs')
        }
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        def ids = new HashSet<String>()
        if (deleteRequest.ids) {
            ids.addAll(deleteRequest.ids)
        }
        if (deleteRequest.idlist) {
            ids.addAll(deleteRequest.idlist.split(','))
        }

        def successful = []
        def deleteerrs = []
        ids.sort().each {jobid ->
            def result = scheduledExecutionService.deleteScheduledExecutionById(jobid, framework, session.user, 'deleteBulk')
            if (result.errorCode) {
                deleteerrs << [id: jobid, errorCode: result.errorCode, message: g.message(code: result.errorCode, args: ['Job ID', jobid])]
            }else if (result.error) {
                deleteerrs << result.error
            } else {
                successful << result.success
            }
        }
        flash.bulkDeleteResult = [success: successful, errors: deleteerrs]
        redirect(controller: 'menu', action: 'jobs')
    }

    /**
     * POST a single job definition to a
     * @return
     */
    def apiJobCreateSingle(){
        log.debug("ScheduledExecutionController: apiJobUpdateSingle " + params)
        def fileformat = params.format ?: 'xml'
        def parseresult

        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }
        if(ScheduledExecution.getByIdOrUUID(params.id)){
            //job already exists, cannot create
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_CONFLICT,
                    code: 'api.error.jobs.create.exists', args: [params.id]])
        }
        if (request.contentType.contains('text/xml')) {
            //read input stream
            parseresult = scheduledExecutionService.parseUploadedFile(request.getInputStream(), fileformat)
        } else {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.missing-file', args: null])
        }
        if (parseresult.errorCode) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: parseresult.errorCode, args: parseresult.args])
        }

        if (parseresult.error) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.invalid', args: [fileformat, parseresult.error]])
        }
        def jobset = parseresult.jobset
        if (jobset.size() != 1) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.update.incorrect-document-content'])
        }
        if (params.project) {
            jobset*.project = params.project
        }
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

        if (!frameworkService.authorizeProjectResourceAll(framework, [type: 'resource', kind: 'job'],
                [AuthConstants.ACTION_CREATE], jobset[0].project)) {

            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Create Job', 'Project', jobset[0].project]])
        }

        jobset*.uuid = params.id
        def changeinfo = [user: session.user, method: 'apiJobCreateSingle']
        String roleList = request.subject.getPrincipals(Group.class).collect { it.name }.join(",")
        def loadresults = scheduledExecutionService.loadJobs(jobset, 'create', 'preserve', session.user, roleList, changeinfo, framework)

        def jobs = loadresults.jobs
        def jobsi = loadresults.jobsi
        def msgs = loadresults.msgs
        def errjobs = loadresults.errjobs
        def skipjobs = loadresults.skipjobs

        if(jobs){
            response.addHeader('Location',apiService.apiHrefForJob(jobs[0]))
            return apiService.renderSuccessXml(HttpServletResponse.SC_CREATED,response){
                renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
            }
        }else{
            return apiService.renderErrorXml(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response) {
                renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
            }
        }
    }
    /**
     * Update a job via PUT
     * @return
     */
    def apiJobUpdateSingle(){
        log.debug("ScheduledExecutionController: apiJobUpdateSingle " + params)
        def fileformat = params.format ?: 'xml'
        def parseresult
        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }
        def scheduledExecution = ScheduledExecution.getByIdOrUUID(params.id)
        if (!apiService.requireExists(response, scheduledExecution,['Job ID',params.id])) {
            //job does not exist
            return
        }
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_UPDATE],
                scheduledExecution.project)) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Update', 'Job ID', params.id]])
        }
        if(request.contentType.contains('text/xml')){
            //read input stream
            parseresult = scheduledExecutionService.parseUploadedFile(request.getInputStream(), fileformat)
        } else {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.missing-file', args: null])
        }
        if (parseresult.errorCode) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: parseresult.errorCode, args: parseresult.args])
        }

        if (parseresult.error) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.invalid', args: [fileformat, parseresult.error]])
        }
        def jobset = parseresult.jobset
        if(jobset.size()!=1){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.update.incorrect-document-content'])
        }
        if (params.project) {
            jobset*.project = params.project
        }
        jobset*.uuid=params.id
        def changeinfo = [user: session.user, method: 'apiJobUpdateSingle']
        String roleList = request.subject.getPrincipals(Group.class).collect { it.name }.join(",")
        def loadresults = scheduledExecutionService.loadJobs(jobset, 'update', 'preserve', session.user, roleList, changeinfo, framework)

        def jobs = loadresults.jobs
        def jobsi = loadresults.jobsi
        def msgs = loadresults.msgs
        def errjobs = loadresults.errjobs
        def skipjobs = loadresults.skipjobs


        if (jobs) {
            return apiService.renderSuccessXml(response) {
                delegate.'link'(href: apiService.apiHrefForJob(jobs[0]), rel: 'get')
                success {
                    delegate.'message'(g.message(code: 'api.success.job.create.message', args: [params.id]))
                }
                renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
            }
        } else {
            return apiService.renderErrorXml(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response) {
                renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
            }
        }
    }
    /**
     * Delete a set of jobs as specified in the idlist parameter.
     * Only allowed via DELETE http method
     * API: DELETE job definitions: /api/5/jobs/delete, version 5
    */
    def apiJobDeleteBulk = {ApiBulkJobDeleteRequest deleteRequest->
        log.debug("ScheduledExecutionController: apiJobDeleteBulk : params: " + params)
        if (!apiService.requireAnyParameters(params, response, ['ids', 'idlist'])) {
            return
        }
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

        def ids = new HashSet<String>()
        if(params.id){
            ids.add(params.id)
        }else{
            if (deleteRequest.ids){
                ids.addAll(deleteRequest.ids)
            }
            if(deleteRequest.idlist){
                ids.addAll(deleteRequest.idlist.split(','))
            }
        }

        def successful = []
        def deleteerrs=[]
        ids.sort().each{jobid->
            def result = scheduledExecutionService.deleteScheduledExecutionById(jobid,framework,session.user, 'apiJobDeleteBulk')
            if (result.errorCode) {
                deleteerrs << [id:jobid,errorCode:result.errorCode,message: g.message(code: result.errorCode, args: ['Job ID', jobid])]
            }else if (result.error) {
                deleteerrs<< result.error
            } else {
                successful<< result.success
            }
        }
        return apiService.renderSuccessXml(response) {
            delegate.'deleteJobs'(requestCount: ids.size(), allsuccessful:(successful.size()==ids.size())){
                if(successful){
                    delegate.'succeeded'(count:successful.size()) {
                        successful.each{del->
                            delegate.'deleteJobResult'(id:del.job.extid,){
                                delegate.'message'(del.message)
                            }
                        }
                    }
                }
                if(deleteerrs){
                    delegate.'failed'(count: deleteerrs.size()) {
                        deleteerrs.each{del->
                            delegate.'deleteJobResult'(id:del.id,errorCode:del.errorCode){
                                delegate.'error'(del.message)
                            }
                        }
                    }
                }
            }
        }
    }

    def edit = {
        log.debug("ScheduledExecutionController: edit : params: " + params)
        def scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        def crontab = [:]
        if(!scheduledExecution) {
            flash.message = "ScheduledExecution not found with id ${params.id}"
            return redirect(action:index, params:params)
        }

        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_UPDATE, AuthConstants.ACTION_READ], scheduledExecution.project)) {
            return unauthorized("Update Job ${params.id}")
        }
        //clear session workflow
        if(session.editWF ){
            session.removeAttribute('editWF');
            session.removeAttribute('undoWF');
            session.removeAttribute('redoWF');
        }
        //clear session opts
        if(session.editOPTS ){
            session.removeAttribute('editOPTS');
            session.removeAttribute('undoOPTS');
            session.removeAttribute('redoOPTS');
        }
        def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
        def stepTypes = frameworkService.getStepPluginDescriptions(framework)
        crontab = scheduledExecution.timeAndDateAsBooleanMap()
        return [ scheduledExecution:scheduledExecution, crontab:crontab,params:params,
                notificationPlugins: notificationService.listNotificationPlugins(),
                nextExecutionTime:scheduledExecutionService.nextExecutionTime(scheduledExecution),
                authorized:scheduledExecutionService.userAuthorizedForJob(request,scheduledExecution,framework),
                nodeStepDescriptions: nodeStepTypes,
                stepDescriptions:stepTypes]
    }



    def update = {
        Framework framework=frameworkService.getFrameworkFromUserSession(session, request)
        def changeinfo=[method:'update',change:'modify',user:session.user]

        //pass session-stored edit state in params map
        transferSessionEditState(session, params,params.id)
        def roleList=request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def result = scheduledExecutionService._doupdate(params,session.user, roleList, framework, changeinfo)
        def scheduledExecution=result.scheduledExecution
        def success = result.success
        if(!scheduledExecution){
            flash.message = "ScheduledExecution not found with id ${params.id}"
            log.warn("update: there was no object by id: " +params.id+". redirecting to edit.")
            redirect(controller:'menu',action:'jobs')
        }else if(result.unauthorized){
            return unauthorized(result.message)
        }else if (!success){
            if(scheduledExecution.errors){
                log.debug scheduledExecution.errors.allErrors.collect {g.message(error: it)}.join(", ")
            }
            request.message="Error updating Job "
            if(result.message){
                request.message+=": "+result.message
            }

            if(!scheduledExecution.isAttached()) {
                scheduledExecution.attach()
            }else{
                scheduledExecution.refresh()
            }
            def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
            def stepTypes = frameworkService.getStepPluginDescriptions(framework)
            return render(view:'edit', model: [scheduledExecution:scheduledExecution,
                       nextExecutionTime:scheduledExecutionService.nextExecutionTime(scheduledExecution),
                    notificationValidation: params['notificationValidation'],
                    nodeStepDescriptions: nodeStepTypes,
                    stepDescriptions: stepTypes,
                    notificationPlugins: notificationService.listNotificationPlugins(),
                    params:params
                   ])
        }else{

            clearEditSession('_new')
            clearEditSession(scheduledExecution.id.toString())
            flash.savedJob=scheduledExecution
            flash.savedJobMessage="Saved changes to Job"
            scheduledExecutionService.logJobChange(changeinfo,scheduledExecution.properties)
            redirect(controller: 'scheduledExecution', action: 'show', params: [id: scheduledExecution.extid])
        }
    }

    def copy = {
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        //authorize
        if (!frameworkService.authorizeProjectResourceAll(framework, [type: 'resource', kind: 'job'], [AuthConstants.ACTION_CREATE], session.project)) {
            return unauthorized("Create a Job")
        }
        def user = (session?.user) ? session.user : "anonymous"
        def rolelist = (session?.roles) ? session.roles : []
        log.debug("ScheduledExecutionController: create : params: " + params)

        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if(!scheduledExecution){
            flash.message = "ScheduledExecution not found with id ${params.id}"
            log.debug("update: there was no object by id: " +params.id+". redirecting to menu.")
            redirect(action:index)
            return;
        }
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_READ], scheduledExecution.project)) {
            return unauthorized("Read Job ${params.id}")
        }
        def newScheduledExecution = new ScheduledExecution()
        newScheduledExecution.properties = new java.util.HashMap(scheduledExecution.properties)
        newScheduledExecution.id=null
        newScheduledExecution.uuid=null
        newScheduledExecution.nextExecution=null
        //set session new workflow
        WorkflowController.getSessionWorkflow(session,null,new Workflow(scheduledExecution.workflow))
        if(scheduledExecution.options){
            def editopts = [:]

            scheduledExecution.options.each {Option opt ->
                editopts[opt.name] = opt.createClone()
            }
            EditOptsController.getSessionOptions(session,null,editopts)
        }
        def crontab = [:]
        if(newScheduledExecution.scheduled){
            crontab=newScheduledExecution.timeAndDateAsBooleanMap()
        }
        def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
        def stepTypes = frameworkService.getStepPluginDescriptions(framework)
        render(view:'create',model: [ scheduledExecution:newScheduledExecution, crontab:crontab,params:params,
                iscopy:true,
                authorized:scheduledExecutionService.userAuthorizedForJob(request,scheduledExecution,framework),
                nodeStepDescriptions: nodeStepTypes,
                stepDescriptions: stepTypes,
                notificationPlugins: notificationService.listNotificationPlugins()])

    }
    /**
     * action to populate the Create form with execution info from a previous (transient) execution
     */
    def createFromExecution={

        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        if (!frameworkService.authorizeProjectResourceAll(framework, [type: 'resource', kind: 'job'], [AuthConstants.ACTION_CREATE],session.project) && !frameworkService.authorizeProjectResource(framework,[type:'adhoc'], AuthConstants.ACTION_RUN,session.project)) {
            return unauthorized("Create a Job")
        }
        log.debug("ScheduledExecutionController: create : params: " + params)
        Execution execution = Execution.get(params.executionId)
        if(!execution){
            flash.message = "Execution not found with id ${params.executionId}"
            redirect(controller:'execution',action:'follow',id:params.executionId)
        }
        def props=[:]
        props.putAll(execution.properties)
        props.workflow=new Workflow(execution.workflow)
        if(params.failedNodes && 'true'==params.failedNodes){
            //replace the node filter with the failedNodeList from the execution
            props = props.findAll{!(it.key=~/^node(In|Ex)clude.*$/)}
            props.nodeIncludeName=execution.failedNodeList
        }
        params.putAll(props)
        //clear session workflow
        if(session.editWF ){
            session.removeAttribute('editWF');
            session.removeAttribute('undoWF');
            session.removeAttribute('redoWF');
        }
        if(session.editOPTS ){
            session.removeAttribute('editOPTS');
            session.removeAttribute('undoOPTS');
            session.removeAttribute('redoOPTS');
        }
        //store workflow in session
        def wf=WorkflowController.getSessionWorkflow(session,null,props.workflow)
        session.editWFPassThru=true

        def model=create.call()
        render(view:'create',model:model)
    }
    private  unauthorized(String action, boolean fragment=false){
        if(!fragment){
            response.setStatus(403)
        }
        flash.title = "Unauthorized"
        flash.error = "${request.remoteUser} is not authorized to: ${action}"
        response.setHeader(Constants.X_RUNDECK_ACTION_UNAUTHORIZED_HEADER, flash.error)
        render(template: fragment?'/common/errorFragment':'/common/error', model: [:])
    }
    def create = {

        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        //authorize
        if(!frameworkService.authorizeProjectResourceAll(framework, [type: 'resource', kind: 'job'], [AuthConstants.ACTION_CREATE], session.project) && !frameworkService.authorizeProjectResource(framework, [type: 'adhoc'], AuthConstants.ACTION_RUN, session.project)){
            return unauthorized("Create a Job")
        }

        def user = (session?.user) ? session.user : "anonymous"
        log.debug("ScheduledExecutionController: create : params: " + params)
        def scheduledExecution = new ScheduledExecution()
        scheduledExecution.loglevel = servletContext.getAttribute("LOGLEVEL_DEFAULT")?servletContext.getAttribute("LOGLEVEL_DEFAULT"):"WARN"
        scheduledExecution.properties = params

        scheduledExecution.jobName = (params.command) ? params.command + " Job" : ""
        def cal = java.util.Calendar.getInstance()
        scheduledExecution.minute = String.valueOf(cal.get(java.util.Calendar.MINUTE))
        scheduledExecution.hour = String.valueOf(cal.get(java.util.Calendar.HOUR_OF_DAY))
        scheduledExecution.user = user
        scheduledExecution.userRoleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        if(params.project ){

            if(!frameworkService.existsFrameworkProject(params.project,framework) ) {
                scheduledExecution.errors.rejectValue('project','scheduledExecution.project.message',[params.project].toArray(),'FrameworkProject was not found: {0}')
            }
            scheduledExecution.argString=params.argString
        }
        //clear session workflow
        if(session.editWFPassThru){
            //do not clear the session's editWF , as this action was called by createFromExecution
            session.removeAttribute('editWFPassThru')
        }else if (session.editWF ){
            session.removeAttribute('editWF');
            session.removeAttribute('undoWF');
            session.removeAttribute('redoWF');
        }//clear session workflow
        if(session.editOPTSPassThru){
            //do not clear the session's editWF , as this action was called by createFromExecution
            session.removeAttribute('editOPTSPassThru')
        }else if (session.editOPTS ){
            session.removeAttribute('editOPTS');
            session.removeAttribute('undoOPTS');
            session.removeAttribute('redoOPTS');
        }

        def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
        def stepTypes = frameworkService.getStepPluginDescriptions(framework)
        log.debug("ScheduledExecutionController: create : now returning model data to view...")
        return ['scheduledExecution':scheduledExecution,params:params,crontab:[:],
                nodeStepDescriptions: nodeStepTypes, stepDescriptions: stepTypes,
                notificationPlugins: notificationService.listNotificationPlugins()]
    }

    private clearEditSession(id='_new'){
        clearOPTSEditSession(id)
        clearWFEditSession(id)
    }
    private clearOPTSEditSession(id){
        session.editOPTS?.remove(id)
        session.undoOPTS?.remove(id)
        session.redoOPTS?.remove(id)
    }

    private clearWFEditSession(id) {
        session.editWF?.remove(id)
        session.undoWF?.remove(id)
        session.redoWF?.remove(id)
    }

    static void transferSessionEditState(session,params,id){
        //pass session-stored edit state in params map
        if (params['_sessionwf'] && session.editWF && null != session.editWF[id]) {
            params['_sessionEditWFObject'] = session.editWF[id]
        }
        if (params['_sessionopts'] && session.editOPTS && null != session.editOPTS[id]) {
            params['_sessionEditOPTSObject'] = session.editOPTS[id]
        }
    }


    def saveAndExec = {
        log.debug("ScheduledExecutionController: saveAndExec : params: " + params)
        def changeinfo = [user: session.user, change: 'create', method: 'saveAndExec']
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        if (!frameworkService.authorizeProjectResourceAll(framework, [type: 'resource', kind: 'job'], [AuthConstants.ACTION_CREATE], session.project)) {
            unauthorized("Create a Job")
            return [success: false]
        }

        //pass session-stored edit state in params map
        transferSessionEditState(session,params,'_new')
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def result = scheduledExecutionService._dosave(params,session.user,roleList,framework, changeinfo)
        def scheduledExecution = result.scheduledExecution
        if(result.success && scheduledExecution && scheduledExecution.id){

            clearEditSession()
            params.id=scheduledExecution.extid
            scheduledExecutionService.logJobChange(changeinfo, scheduledExecution.properties)
            if(!scheduledExecution.scheduled){
                return redirect(action:'execute',id:scheduledExecution.extid)
            }else{
                return redirect(action:'show',id:scheduledExecution.extid)
            }
        }else{

            if(scheduledExecution){
                scheduledExecution.errors.allErrors.each { log.warn(it.defaultMessage) }
            }
            flash.message=g.message(code:'ScheduledExecutionController.save.failed')

            def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
            def stepTypes = frameworkService.getStepPluginDescriptions(framework)
            return render(view:'create',model:[scheduledExecution:scheduledExecution,params:params,
                    projects: frameworkService.projects(framework), nodeStepDescriptions: nodeStepTypes,
                    stepDescriptions: stepTypes,
                    notificationPlugins: notificationService.listNotificationPlugins(),
                    notificationValidation: params['notificationValidation']])
        }
    }
    /**
     * Action to upload jobs.xml and execute it immediately.
     */
    def uploadAndExecute = {
        log.debug("ScheduledExecutionController: upload " + params)
        def fileformat = params.fileformat ?: 'xml'
        def parseresult
        if (request instanceof MultipartHttpServletRequest) {
            def file = request.getFile("xmlBatch")
            if (!file || file.empty) {
                flash.message = "No file was uploaded."
                return
            }
            parseresult = scheduledExecutionService.parseUploadedFile(file.getInputStream(), fileformat)
        } else if (params.xmlBatch) {
            String fileContent = params.xmlBatch
            parseresult = scheduledExecutionService.parseUploadedFile(fileContent, fileformat)
        } else {
            return
        }
        def jobset
        if (parseresult.errorCode) {
            parseresult.error = message(code: parseresult.errorCode, args: parseresult.args)
        }
        if (parseresult.error) {
            flash.error = parseresult.error
            if (params.xmlreq) {
                return xmlerror()
            } else {
                render(view: 'upload')
                return
            }
        }
        jobset = parseresult.jobset
        def changeinfo = [user: session.user, method: 'upload']
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def loadresults = scheduledExecutionService.loadJobs(jobset, params.dupeOption, session.user, roleList, changeinfo, framework)

        def jobs = loadresults.jobs
        def jobsi = loadresults.jobsi
        def msgs = loadresults.msgs
        def errjobs = loadresults.errjobs
        def skipjobs = loadresults.skipjobs

        def reserrors = []
        def ressuccess=[]

        if(!errjobs){
            //run the jobs and forward to nowrunning
            jobsi.each{ Map map->
                def ScheduledExecution scheduledExecution=map.scheduledExecution
                def entrynum=map.entrynum
                def properties=[:]
                properties.putAll(scheduledExecution.properties)
                properties.user=params.user
                properties.request = request
                def execresults = executionService.executeScheduledExecution(scheduledExecution,framework,request.subject,[user:request.remoteUser])
    //            System.err.println("transient execute result: ${execresults}");
                execresults.entrynum=entrynum
                if(execresults.error || !execresults.success){
                    reserrors<<execresults
                } else {
                    ressuccess<<execresults
                }
            }
        }

        if (!params.xmlreq) {

            render(view: 'upload',model:[jobs: jobs, errjobs: errjobs, skipjobs: skipjobs, execerrors:reserrors,execsuccess:ressuccess,
                nextExecutions: scheduledExecutionService.nextExecutionTimes(jobs.grep { it.scheduled }),
                messages: msgs,
                didupload: true])
        } else {
            //TODO: update commander's jobs upload task to submit XML content directly instead of via uploaded file, and use proper
            //TODO: grails content negotiation
            response.setHeader(Constants.X_RUNDECK_RESULT_HEADER, "Jobs Uploaded. Succeeded: ${jobs.size()}, Failed: ${errjobs.size()}, Skipped: ${skipjobs.size()}")
            render(contentType: "text/xml") {
                result(error: false) {
                    renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
                }
            }
        }

    }
    
    /**
     * execute the job defined via input parameters, but do not store it.
     */
    def runAdhocInline = {
        def results=runAdhoc()
        if(results.failed){
            results.error=results.message
        } else {
            log.debug("ExecutionController: immediate execution scheduled (${results.id})")
        }
        return render(contentType:'text/json'){
            if(results.error){
                delegate['error']=results.error
            }else{
                success='true'
                id=results.id
            }
        }
    }
    private def runAdhoc(){
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        params["user"] = (session?.user) ? session.user : "anonymous"
        params.request = request
        params.jobName='Temporary_Job'
        params.groupPath='adhoc'

        if (params.asUser && apiService.requireVersion(request,response,ApiRequestFilters.V5)) {
            //authorize RunAs User
            if (!frameworkService.authorizeProjectResource(framework, [type: 'adhoc'], AuthConstants.ACTION_RUNAS, params.project)) {

                def msg = g.message(code: "api.error.item.unauthorized", args: ['Run as User', 'Run', 'Adhoc'])
                return [failed:true,error: 'unauthorized', message: msg]
            }
            params['user'] = params.asUser
        }
        if(params.exec){
            params.doNodedispatch=true
            params.nodeKeepgoing=true
            params.nodeThreadcount=1
            params.workflow = new Workflow(commands: [new CommandExec(adhocRemoteString: params.remove('exec'), adhocExecution: true)])
            params.description = params.description ?: ""
        }

        //pass session-stored edit state in params map
        transferSessionEditState(session, params,'_new')
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def result= scheduledExecutionService._dovalidate(params,session.user,roleList,framework)
        def ScheduledExecution scheduledExecution=result.scheduledExecution
        def failed=result.failed
        if(!failed){
            return _transientExecute(scheduledExecution,params,framework,request.subject)
        }else{
            return [success:false,failed:true,invalid:true,message:'Job configuration was incorrect.',scheduledExecution:scheduledExecution,params:params]
        }
    }
    /**
     * execute the job defined via input parameters, but do not store it.
     */
    def execAndForget = {
        def results = runAdhoc()
        if(results.error=='unauthorized'){
            log.error(results.message)
            flash.error=results.message
            render(view:"/common/execUnauthorized",model:[scheduledExecution:results.scheduledExecution])
            return
        }else if(results.error){
            log.error(results.message)
            flash.error=results.message
            render(view:"/common/error",model:[error:results.message])
            return
        }else if(results.failed){
            def scheduledExecution=results.scheduledExecution
            scheduledExecution.jobName = ''
            scheduledExecution.errors.allErrors.each { log.warn(it.defaultMessage) }
            flash.message=results.message
            Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

            def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
            def stepTypes = frameworkService.getStepPluginDescriptions(framework)
            render(view:'create',model:[scheduledExecution:scheduledExecution,params:params,
                    nodeStepDescriptions: nodeStepTypes, stepDescriptions: stepTypes])
        } else {
            log.debug("ExecutionController: immediate execution scheduled (${results.id})")
            redirect(controller:"execution", action:"follow",id:results.id)
        }
    }
    
    /**
    * Execute a transient ScheduledExecution and return execution data: [execution:Execution,id:Long]
     * if there is an error, return [error:'type',message:errormesg,...]
     */
    private Map _transientExecute(ScheduledExecution scheduledExecution, Map params, Framework framework, Subject subject){
        def object
        def isauth = scheduledExecutionService.userAuthorizedForAdhoc(params.request,scheduledExecution,framework)
        if (!isauth){
            def msg=g.message(code:'unauthorized.job.run.user',args:[params.user])
            return [success:false,error:'unauthorized',message:msg]
        }
        params.workflow=new Workflow(scheduledExecution.workflow)
        params.argString=scheduledExecution.argString

        def Execution e
        try {
            e = executionService.createExecutionAndPrep(params, framework, params.user)
        } catch (ExecutionServiceException exc) {
            return [success:false,error:'failed',message:exc.getMessage()]
        }

        def eid = scheduledExecutionService.scheduleTempJob(params.user, subject,params,e);
        return [success:true,execution:e,id:eid]
    }



    def save = {
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        def changeinfo=[user:session.user,change:'create',method:'save']

        //pass session-stored edit state in params map
        transferSessionEditState(session, params,'_new')
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def result = scheduledExecutionService._dosave(params,session.user,roleList,framework, changeinfo)
        def scheduledExecution = result.scheduledExecution
        if(result.success && scheduledExecution.id){
            clearEditSession()
            flash.savedJob=scheduledExecution
            flash.savedJobMessage="Created new Job"
            scheduledExecutionService.logJobChange(changeinfo,scheduledExecution.properties)
            return redirect(controller:'scheduledExecution',action:'show',params:[id:scheduledExecution.extid])
        }else{
            if(scheduledExecution){
                scheduledExecution.errors.allErrors.each { log.warn(it.defaultMessage) }
            }
            if (result.unauthorized){
                request.message = result.error
            }else{
                request.message=g.message(code:'ScheduledExecutionController.save.failed')
            }
        }

        def nodeStepTypes = frameworkService.getNodeStepPluginDescriptions(framework)
        def stepTypes = frameworkService.getStepPluginDescriptions(framework)
        render(view: 'create', model: [scheduledExecution: scheduledExecution, params: params,
                projects: frameworkService.projects(framework), nodeStepDescriptions: nodeStepTypes,
                stepDescriptions: stepTypes,
                notificationPlugins: notificationService.listNotificationPlugins(),
                notificationValidation:params['notificationValidation']
        ])
    }


    def upload ={
        log.debug("ScheduledExecutionController: upload " + params)

        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        
        def fileformat = params.fileformat ?: 'xml'
        def parseresult
        if (request.method == 'POST') {
            if(params.xmlBatch && params.xmlBatch instanceof String) {
                String fileContent = params.xmlBatch
                parseresult = scheduledExecutionService.parseUploadedFile(fileContent, fileformat)
            } else if(params.xmlBatch && params.xmlBatch instanceof CommonsMultipartFile) {
                InputStream fileContent = params.xmlBatch.inputStream
                parseresult = scheduledExecutionService.parseUploadedFile(fileContent, fileformat)
            } else if (request instanceof MultipartHttpServletRequest) {
                def file = request.getFile("xmlBatch")
                if (!file || file.empty) {
                    flash.message = "No file was uploaded."
                    return
                }
                parseresult = scheduledExecutionService.parseUploadedFile(file.getInputStream(), fileformat)
            } else {
                flash.message = "No file was uploaded."
                return
            }
        }else{
            return
        }
        def jobset
        if(parseresult.errorCode){
            parseresult.error=message(code:parseresult.errorCode,args:parseresult.args)
        }
        if(parseresult.error){
            if(params.xmlreq){
                flash.error = parseresult.error
                return xmlerror()
            }else{
                render(view:'upload',model:[uploadError:parseresult.error])
                return
            }
        }
        jobset=parseresult.jobset
        jobset*.project=params.project
        def changeinfo = [user: session.user,method:'upload']
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def loadresults = scheduledExecutionService.loadJobs(jobset, params.dupeOption, params.uuidOption,
                session.user, roleList, changeinfo, framework)


        def jobs = loadresults.jobs
        def jobsi = loadresults.jobsi
        def msgs = loadresults.msgs
        def errjobs = loadresults.errjobs
        def skipjobs = loadresults.skipjobs

        if(!params.xmlreq){
            return [jobs: jobs, errjobs: errjobs, skipjobs: skipjobs,
                nextExecutions:scheduledExecutionService.nextExecutionTimes(jobs.grep{ it.scheduled }), 
                messages: msgs,
                didupload: true]
        }else{
            //TODO: update commander's jobs upload task to submit XML content directly instead of via uploaded file, and use proper
            //TODO: grails content negotiation
            response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,"Jobs Uploaded. Succeeded: ${jobs.size()}, Failed: ${errjobs.size()}, Skipped: ${skipjobs.size()}")
                render(contentType:"text/xml"){
                    result(error:false){
                        renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
                    }
                }
        }
    }


    def execute = {
        return redirect(action: 'show',params:params)
    }

    private _prepareExecute(ScheduledExecution scheduledExecution, final def framework){
        def model=[scheduledExecution:scheduledExecution]
        model.authorized=true
        //test nodeset to make sure there are matches
        if(scheduledExecution.doNodedispatch){
            NodeSet nset = ExecutionService.filtersAsNodeSet(scheduledExecution)
            def project=frameworkService.getFrameworkProject(scheduledExecution.project, framework)
//            def nodes=project.getNodes().filterNodes(nset)
            model.nodeset=nset
            def nodes= com.dtolabs.rundeck.core.common.NodeFilter.filterNodes(nset, project.getNodeSet()).nodes
            if(!nodes || nodes.size()<1){
                //error
                model.nodesetempty=true
            }
            else if(grailsApplication.config.gui.execution.summarizedNodes != 'false') {
                model.nodes=nodes
                model.nodemap=[:]
                model.tagsummary=[:]
                model.grouptags=[:]
                //summarize node groups
                def namegroups=[other: new TreeList()]
                nodes.each{INodeEntry node->
                    def name=node.nodename
                    model.nodemap[name]=node
                    def matcher = (name=~ /^(.*\D)(\d+)/)
                    def matcher2 = (name =~ /^(\d+)(\D.*)/)
                    def groupname
                    if(matcher.matches()){
                        def pat=matcher.group(1)
                        def num = matcher.group(2)
                        groupname= pat + '.*'

                    }else if(matcher2.matches()){
                        def pat = matcher2.group(2)
                        def num = matcher2.group(1)
                        groupname= '.*' + pat

                    }else{
                        groupname='other'
                    }
                    if (!namegroups[groupname]) {
                        namegroups[groupname] = new TreeList()
                    }
                    namegroups[groupname] << name
                    //summarize tags
                    def tags = node.getTags()
                    if (tags) {
                        tags.each { tag ->
                            if (!model.tagsummary[tag]) {
                                model.tagsummary[tag] = 1
                            } else {
                                model.tagsummary[tag]++
                            }
                            if(!model.grouptags[groupname]){
                                model.grouptags[groupname]=[(tag):1]
                            }else if (!model.grouptags[groupname][tag]) {
                                model.grouptags[groupname][tag]=1
                            }else{
                                model.grouptags[groupname][tag]++
                            }
                        }
                    }
                }
                def singles=[]
                namegroups.keySet().grep {it!='other'&&namegroups[it].size() == 1}.each{
                    namegroups['other'].addAll(namegroups[it])
                    model.grouptags[it]?.each{tag,v->
                        if (!model.grouptags['other']) {
                            model.grouptags['other'] = [(tag): v]
                        } else if (!model.grouptags['other'][tag]) {
                            model.grouptags['other'][tag] = v
                        } else {
                            model.grouptags['other'][tag] += v
                        }
                    }
                    singles<<it
                }
                singles.each{
                    namegroups.remove(it)
                    model.grouptags.remove(it)
                }
                if(!namegroups['other']){
                    namegroups.remove('other')
                }

                model.namegroups=namegroups
            }else{
                model.nodes = nodes
            }
            //check nodeset filters for variable expansion
            def varfound=NodeSet.FILTER_ENUM.find{filter->
                (nset.include?filter.value(nset.include)?.contains("\${"):false)||(nset.exclude?filter.value(nset.exclude)?.contains("\${"):false)
            }
            if(varfound){
                model.nodesetvariables=true
            }

        }

        if(params.failedNodes){
            model.failedNodes=params.failedNodes
        }
        if(params.retryFailedExecId){
            Execution e = Execution.get(params.retryFailedExecId)
            if(e){
                model.failedNodes=e.failedNodeList
            }
        }
        if(params.retryExecId){
            Execution e = Execution.get(params.retryExecId)
            if(e){
                model.selectedoptsmap=frameworkService.parseOptsFromString(e.argString)
            }
        }else if(params.argString){
            model.selectedoptsmap = frameworkService.parseOptsFromString(params.argString)
        }
        model.localNodeName=framework.getFrameworkNodeName()

        //determine option dependencies based on valuesURl embedded references
        //map of option name to list of option names which depend on it
        def depopts=[:]
        //map of option name to list of option names it depends on
        def optdeps=[:]
        scheduledExecution.options.each { Option opt->
            if(opt.realValuesUrl){
                (opt.realValuesUrl=~/\$\{option\.([^.}\s]+?)\.value\}/ ).each{match,oname->
                    if(oname==opt.name){
                        return
                    }
                    //add opt to list of dependents of oname
                    if(!depopts[oname]){
                        depopts[oname]=[opt.name]
                    }else{
                        depopts[oname] << opt.name
                    }
                    //add oname to list of dependencies of opt
                    if(!optdeps[opt.name]){
                        optdeps[opt.name]=[oname]
                    }else{
                        optdeps[opt.name] << oname
                    }
                }
            }
        }
        model.dependentoptions=depopts
        model.optiondependencies=optdeps
        //topo sort the dependencies
        def toporesult = toposort(scheduledExecution.options*.name, depopts, optdeps)
        model.optionordering= toporesult.result
        if(scheduledExecution.options && !toporesult.result){
            log.warn("Cyclic dependency for options for job ${scheduledExecution.extid}: (${toporesult.cycle})")
            model.optionsDependenciesCyclic=true
        }

        return model
    }
    private deepClone(Map map) {
        def copy = [:]
        map.each { k, v ->
            if (v instanceof List){
                copy[k] = v.clone()
            }
            else {
                copy[k] = v
            }
        }
        return copy
    }

    /**
     * Return topo sorted list of nodes, if acyclic, preserving
     * order of input node list for independent nodes
     * @param nodes
     * @param oedgesin
     * @param iedgesin
     * @return
     */
    private toposort(List nodes,Map oedgesin,Map iedgesin){
        def Map oedges = deepClone(oedgesin)
        def Map iedges = deepClone(iedgesin)
        def l = new ArrayList()
        def s = new ArrayList(nodes.findAll {!iedges[it]})
        while(s){
            def n = s.first()
            s.remove(n)
            l.add(n)
            //for each node dependent on n
            def edges = new ArrayList()
            if(oedges[n]){
                edges.addAll(oedges[n])
            }
            edges.each{p->
                oedges[n].remove(p)
                iedges[p].remove(n)
                if(!iedges[p]){
                    s<<p
                }
            }
        }
        if (iedges.any {it.value} || oedges.any{it.value}){
            //cyclic graph
            return [cycle: iedges]
        }else{
            return [result:l]
        }
    }
    def executeFragment = {
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        def scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_RUN], scheduledExecution.project)) {
            return unauthorized("Execute Job ${scheduledExecution.extid}",true)
        }
        def model = _prepareExecute(scheduledExecution, framework)
        if(params.dovalidate){
            model.jobexecOptionErrors=session.jobexecOptionErrors
            model.selectedoptsmap=session.selectedoptsmap
            session.jobexecOptionErrors=null
            session.selectedoptsmap=null
            model.options=null
        }
        render(template:'execOptionsForm',model:model)
    }

    /**
     * Execute job specified by parameters, and return json results
     */
    def runJobInline = {
        def results = runJob()

        if(results.error=='invalid'){
            session.jobexecOptionErrors=results.errors
            session.selectedoptsmap=results.options
        }
        return render(contentType:'application/json'){
            if(results.failed){
                delegate.error=results.error
                delegate.message=results.message
            }else{
                delegate.success=true
                delegate.id=results.id
                delegate.href=createLink(controller: "execution",action: "follow",id: results.id)
                delegate.follow=(params.follow == 'true')
            }
        }
    }
    def runJobNow = {
        return executeNow()
    }
    def executeNow = {
        def results = runJob()
        if(results.failed){
            log.error(results.message)
            if(results.error=='unauthorized'){
                return render(view:"/common/execUnauthorized",model:results)
            }else {
                def model=show()
                results.error = results.remove('message')
                results.jobexecOptionErrors=results.errors
                results.selectedoptsmap=results.options
                results.putAll(model)
                results.options=null
                return render(view:'show',model:results)
            }
        }else if (results.error){
            log.error(results.error)
            if(results.code){
                response.setStatus (results.code)
            }
            return render(template:"/common/error",model:results)
        }else if(params.follow=='true'){
            redirect(controller:"execution", action:"follow",id:results.id)
        }else {
            redirect(controller: "scheduledExecution", action: "show", id: params.id)
        }
    }
    private Map runJob () {
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)
        params["user"] = (session?.user) ? session.user : "anonymous"
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if (!scheduledExecution) {
//            response.setStatus (404)
            return [error:"No Job found for id: " + params.id,code:404]
        }
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_RUN],
            scheduledExecution.project)) {
            return [success:false,failed:true,error:'unauthorized',message: "Unauthorized: Execute Job ${scheduledExecution.extid}"]
        }
        if(params.extra?.debug=='true'){
            params.extra.loglevel='DEBUG'
        }
        def result = executionService.executeScheduledExecution(scheduledExecution,framework, request.subject,params)

        if (result.error){
            result.failed=true

            return result
        }else{
            log.debug("ExecutionController: immediate execution scheduled")
//            redirect(controller:"execution", action:"follow",id:result.executionId)
            return [success:true, message:"immediate execution scheduled", id:result.executionId]
        }
    }


    /**
    * API Actions
     */


    /**
     * Utility, render content for jobs/import response
     */
    def renderJobsImportApiXML={jobs,jobsi,errjobs,skipjobs, delegate->
        delegate.'succeeded'(count:jobs.size()){
            jobsi.each { Map job ->
                delegate.'job'(index: job.entrynum,href: apiService.apiHrefForJob(job.scheduledExecution)) {
                    id(job.scheduledExecution.extid)
                    name(job.scheduledExecution.jobName)
                    group(job.scheduledExecution.groupPath ?: '')
                    project(job.scheduledExecution.project)
                    url(g.createLink(action: 'show', id: job.scheduledExecution.extid, absolute: true))
                }
            }
        }
        delegate.failed(count:errjobs.size()){
            errjobs.each{ Map job ->
                def jmap=[index:job.entrynum]
                if(job.scheduledExecution.id){
                    jmap.href=apiService.apiHrefForJob(job.scheduledExecution)
                }
                delegate.'job'(jmap){
                    if(job.scheduledExecution.id){
                        id(job.scheduledExecution.extid)
                        url(g.createLink(action:'show',id: job.scheduledExecution.extid, absolute:true))
                    }
                    name(job.scheduledExecution.jobName)
                    group(job.scheduledExecution.groupPath?:'')
                    project(job.scheduledExecution.project)
                    StringBuffer sb = new StringBuffer()
                    job.scheduledExecution?.errors?.allErrors?.each{err->
                        if(sb.size()>0){
                            sb<<"\n"
                        }
                        sb << g.message(error:err)
                    }
                    if(job.errmsg){
                        if(sb.size()>0){
                            sb<<"\n"
                        }
                        sb<<job.errmsg
                    }
                    delegate.'error'(sb.toString())
                }
            }
        }
        delegate.skipped(count:skipjobs.size()){

            skipjobs.each{ Map job ->
                def jmap = [index: job.entrynum]
                if (job.scheduledExecution.id) {
                    jmap.href = apiService.apiHrefForJob(job.scheduledExecution)
                }
                delegate.'job'(jmap){
                    if(job.scheduledExecution.id){
                        id(job.scheduledExecution.extid)
                        url(g.createLink(action:'show',id: job.scheduledExecution.extid,absolute:true))
                    }
                    name(job.scheduledExecution.jobName)
                    group(job.scheduledExecution.groupPath?:'')
                    project(job.scheduledExecution.project)
                    StringBuffer sb = new StringBuffer()
                    if(job.errmsg){
                        if(sb.size()>0){
                            sb<<"\n"
                        }
                        sb<<job.errmsg
                    }
                    delegate.'error'(sb.toString())
                }
            }
        }
    }
    /**
     * API: /jobs/import, version 1
     */
    def apiJobsImport= {
        log.debug("ScheduledExecutionController: upload " + params)
        def fileformat = params.format ?: 'xml'
        def parseresult
        if (!apiService.requireParameters(params,response,['xmlBatch'])) {
            return
        }
        if (request instanceof MultipartHttpServletRequest) {
            def file = request.getFile("xmlBatch")
            if (!file) {
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                        code: 'api.error.jobs.import.missing-file', args: null])
            }
            parseresult = scheduledExecutionService.parseUploadedFile(file.getInputStream(), fileformat)
        }else if (params.xmlBatch) {
            String fileContent = params.xmlBatch
            parseresult = scheduledExecutionService.parseUploadedFile(fileContent, fileformat)
        }else{
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.missing-file', args: null])
        }
        if (parseresult.errorCode) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: parseresult.errorCode, args: parseresult.args])
        }

        if (parseresult.error) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.jobs.import.invalid', args: [fileformat,parseresult.error]])
        }
        def jobset = parseresult.jobset
        if(request.api_version >= ApiRequestFilters.V8){
            //v8 override project using parameter
            if(params.project){
                jobset*.project=params.project
            }
        }
        def changeinfo = [user: session.user,method:'apiJobsImport']
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
        def option = params.uuidOption
        if (request.api_version < ApiRequestFilters.V9) {
            option = null
        }
        def loadresults = scheduledExecutionService.loadJobs(jobset,params.dupeOption, option,session.user, roleList, changeinfo,framework)

        def jobs = loadresults.jobs
        def jobsi = loadresults.jobsi
        def msgs = loadresults.msgs
        def errjobs = loadresults.errjobs
        def skipjobs = loadresults.skipjobs


        apiService.renderSuccessXml(response){
            renderJobsImportApiXML(jobs, jobsi, errjobs, skipjobs, delegate)
        }
    }

    /**
     * API: export job definition: /job/{id}, version 1
     */
    def apiJobExport(){
        log.debug("ScheduledExecutionController: /api/job GET : params: " + params)
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID( params.id )
        if (!apiService.requireExists(response, scheduledExecution,['Job ID',params.id])) {
            return
        }
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_READ], scheduledExecution.project)) {
            return apiService.renderErrorXml(response,[status:HttpServletResponse.SC_FORBIDDEN,
                    code:'api.error.item.unauthorized',args:['Read','Job ID',params.id]])
        }

        withFormat{
            xml{
                def writer = new StringWriter()
                def xml = new MarkupBuilder(writer)
                JobsXMLCodec.encodeWithBuilder([scheduledExecution],xml)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
            yaml{
                render(text:JobsYAMLCodec.encode([scheduledExecution] as List),contentType:"text/yaml",encoding:"UTF-8")
            }
        }
    }
    /**
     * API: Run a job immediately: /job/{id}/run, version 1
     */
    def apiJobRun = {
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)
        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_RUN],
            scheduledExecution.project)) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Run', 'Job ID', params.id]])
        }
        def inparams = [extra: [:]]
        inparams["user"] = (session?.user) ? session.user : "anonymous"
        if(params.asUser && apiService.requireVersion(request,response,ApiRequestFilters.V5)){
            //authorize RunAs User
            if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_RUNAS],
                    scheduledExecution.project)) {
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                        code: 'api.error.item.unauthorized', args: ['Run as User', 'Job ID', params.id]])
            }
            inparams['user']= params.asUser
        }

        if (params.argString) {
            inparams.extra["argString"] = params.argString
        }
        if (params.loglevel) {
            inparams.extra["loglevel"] = params.loglevel
        }
        //convert api parameters to node filter parameters
        def filters = FrameworkController.extractApiNodeFilterParams(params)
        if (filters) {
            inparams.extra['_replaceNodeFilters']='true'
            inparams.extra['doNodedispatch']=true
            filters.each {k, v ->
                inparams.extra[k] = v
            }
            if(null==inparams.extra['nodeExcludePrecedence']){
                inparams.extra['nodeExcludePrecedence'] = true
            }
        }

        def result = executionService.executeScheduledExecution(scheduledExecution, framework, request.subject, inparams)
        if(!result.success){
            if(result.error=='unauthorized'){
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                        code: 'api.error.item.unauthorized', args: ['Execute', 'Job ID', params.id]])
            }else if(result.error=='invalid'){
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                        code: 'api.error.job.options-invalid', args: [result.message]])
            }else{
                //failed
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        code: 'api.error.execution.failed', args: [result.message]])
            }
        }
        def e = result.execution
        return executionService.respondExecutionsXml(response,[e])
    }

    /**
     * API: DELETE job definition: /job/{id}, version 1
     */
    def apiJobDelete() {
        log.debug("ScheduledExecutionController: /api/job DELETE : params: " + params)
        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)
        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }
        def Framework framework = frameworkService.getFrameworkFromUserSession(session, request)
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_DELETE],
                scheduledExecution.project)) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Delete', 'Job ID', params.id]])
        }
        def result = scheduledExecutionService.deleteScheduledExecutionById(params.id, framework, session.user, 'apiJobDelete')
        if (!result.success) {
            if (result.error?.errorCode == 'notfound') {
                apiService.renderErrorXml(response, [status: HttpServletResponse.SC_NOT_FOUND, code: 'api.error.item.doesnotexist',
                        args: ['Job ID', params.id]])
            } else if (result.error?.errorCode == 'unauthorized') {
                apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                        code: 'api.error.item.unauthorized',
                        args: ['Delete', 'Job ID', params.id]]
                )
            } else {
                apiService.renderErrorXml(response, [status: HttpServletResponse.SC_CONFLICT,
                        code: 'api.error.job.delete.failed',
                        args: [result.error.message]]
                )
            }
        } else {
            //return 204 no content
            return render(status: HttpServletResponse.SC_NO_CONTENT)
        }
    }

    /**
     * API: run simple exec: /api/run/command, version 1
     */
    def apiRunCommand={
        if (!apiService.requireParameters(params, response, ['project','exec'])) {
            return
        }
        //test valid project
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)

        def exists=frameworkService.existsFrameworkProject(params.project,framework)
        if (!apiService.requireExists(response, exists, ['project', params.project])) {
            return
        }

        //remote any input parameters that should not be used when creating the execution
        ['options','scheduled'].each{params.remove(it)}
        params.workflow=new Workflow(commands:[new CommandExec(adhocRemoteString:params.remove('exec'), adhocExecution:true)])
        params.description=params.description?:""

        //convert api parameters to node filter parameters
        def filters=FrameworkController.extractApiNodeFilterParams(params)
        if(filters){
            filters['doNodedispatch']=true
            filters.each{k,v->
                params[k]=v
            }
            if (null == params['nodeExcludePrecedence']) {
                params['nodeExcludePrecedence'] = true
            }
        }

        def results=runAdhoc()
        return apiResponseAdhoc(results)
    }


    /**
     * API: run script: /api/run/script, version 1
     */
    def apiRunScript={
        if(!apiService.requireParameters(params,response,['project','scriptFile'])){
            return
        }
        //test valid project
        Framework framework = frameworkService.getFrameworkFromUserSession(session,request)

        def exists=frameworkService.existsFrameworkProject(params.project,framework)
        if (!apiService.requireExists(response, exists, ['project', params.project])) {
            return
        }

        //read attached script content
        def file = request.getFile("scriptFile")
        if(file.empty) {
            return apiService.renderErrorXml(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.run-script.upload.is-empty'])
        }

        def script=new String(file.bytes)

        //remote any input parameters that should not be used when creating the execution
        ['options','scheduled'].each{params.remove(it)}
        def scriptInterpreter = null
        def interpreterArgsQuoted = false
        if (request.api_version >= ApiRequestFilters.V8) {
            scriptInterpreter = params.scriptInterpreter ?: null
            interpreterArgsQuoted = Boolean.parseBoolean(params.interpreterArgsQuoted?.toString())
        }
        params.workflow = new Workflow(commands: [new CommandExec(adhocLocalString: script, adhocExecution: true,
                argString: params.argString, scriptInterpreter: scriptInterpreter,
                interpreterArgsQuoted: interpreterArgsQuoted)])

        params.description=params.description?:""

        //convert api parameters to node filter parameters
        def filters=FrameworkController.extractApiNodeFilterParams(params)
        if(filters){
            filters['doNodedispatch'] = true
            filters.each{k,v->
                params[k]=v
            }
            if (null == params['nodeExcludePrecedence']) {
                params['nodeExcludePrecedence'] = true
            }
        }

        def results=runAdhoc()
        return apiResponseAdhoc(results)
    }

    private apiResponseAdhoc(results){
        if (results.failed) {
            results.error = results.message
        }
        if (!results.success) {
            def errors = [results.error]
            if (results.scheduledExecution) {
                errors = []
                results.scheduledExecution.errors.allErrors.each {
                    errors << g.message(error: it)
                }
            }
            if (results.error == 'unauthorized') {
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                        code: 'api.error.item.unauthorized', args: ['Execute', 'Adhoc', 'Command']])
            } else if (results.error == 'invalid') {
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                        code: 'api.error.execution.invalid', args: [errors.join(", ")]])
            } else {
                //failed
                return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        code: 'api.error.execution.failed', args: [errors.join(", ")]])
            }
        } else {
            return apiService.renderSuccessXml(response) {
                delegate.'success' {
                    message("Immediate execution scheduled (${results.id})")
                }
                delegate.'execution'(id: results.id)
            }
        }
    }

    /**
     * API: run script: /api/run/url, version 4
     */
    def apiRunScriptUrl = {
        if (!apiService.requireVersion(request,response,ApiRequestFilters.V4)) {
            return
        }
        if (!apiService.requireParameters(params, response, ['project','scriptURL'])) {
            return
        }
        //test valid project
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

        def exists = frameworkService.existsFrameworkProject(params.project, framework)
        if (!apiService.requireExists(response, exists, ['project', params.project])) {
            return
        }

        //remote any input parameters that should not be used when creating the execution
        ['options', 'scheduled'].each {params.remove(it)}
        def scriptInterpreter = null
        def interpreterArgsQuoted = false
        if (request.api_version >= ApiRequestFilters.V8) {
            scriptInterpreter = params.scriptInterpreter ?: null
            interpreterArgsQuoted = Boolean.parseBoolean(params.interpreterArgsQuoted?.toString())
        }
        params.workflow = new Workflow(commands: [new CommandExec(adhocFilepath: params.scriptURL, adhocExecution: true,
                argString: params.argString, scriptInterpreter: scriptInterpreter,
                interpreterArgsQuoted: interpreterArgsQuoted)])

        params.description = params.description ?: ""

        //convert api parameters to node filter parameters
        def filters = FrameworkController.extractApiNodeFilterParams(params)
        if (filters) {
            filters['doNodedispatch'] = true
            filters.each {k, v ->
                params[k] = v
            }
            if (null == params['nodeExcludePrecedence']) {
                params['nodeExcludePrecedence'] = true
            }
        }

        def results = runAdhoc()
        return apiResponseAdhoc(results)
    }
    /**
     * API: /api/job/{id}/executions , version 1
     */
    def apiJobExecutions = {
        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }
        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)
        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }

        def state=params['status']
        final statusList = [ExecutionService.EXECUTION_RUNNING, ExecutionService.EXECUTION_ABORTED, ExecutionService.EXECUTION_FAILED, ExecutionService.EXECUTION_SUCCEEDED]
        final domainStatus=[(ExecutionService.EXECUTION_FAILED):'false',
            (ExecutionService.EXECUTION_SUCCEEDED):'true']
        if(state && !(state in statusList)){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.not.inList', args: [params.status, 'status', statusList]])
        }
        def c = Execution.createCriteria()
        def result=c.list{
            delegate.'scheduledExecution'{
                eq('id', scheduledExecution.id)
            }
            if(state== ExecutionService.EXECUTION_RUNNING){
                isNull('dateCompleted')
            }else if(state== ExecutionService.EXECUTION_ABORTED){
                isNotNull('dateCompleted')
                eq('cancelled',true)
            }else if(state){
                isNotNull('dateCompleted')
                eq('cancelled', false)
                eq('status',domainStatus[state])
            }

            if(params.offset){
                firstResult(params.int('offset'))
            }
            if(params.max){
                maxResults(params.int('max'))
            }
            and {
                order('dateCompleted', 'desc')
                order('dateStarted', 'desc')
            }
        }

        return executionService.respondExecutionsXml(response,result)
    }
    /**
     * API: /api/incubator/jobs/takeoverSchedule , version 7
     */
    def apiJobClusterTakeoverSchedule = {
        if (!apiService.requireVersion(request,response,ApiRequestFilters.V7)) {
            return
        }
        //test valid project
        Framework framework = frameworkService.getFrameworkFromUserSession(session, request)

        if (!frameworkService.authorizeApplicationResource(framework, [type: 'resource', kind: 'job'], AuthConstants.ACTION_ADMIN)) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Reschedule Jobs', 'Server', params.serverNodeUUID]])
        }
        if(!frameworkService.isClusterModeEnabled()){
            return apiService.renderSuccessXml(response) {
                message("No action performed, cluster mode is not enabled.")
            }
        }

        def serverUUID
        if(request.format=='json' ){
            def data= request.JSON
            serverUUID = data?.server?.uuid
        }else if(request.format=='xml' || !request.format){
            def data= request.XML
            if(data.name()=='server'){
                serverUUID = data.'@uuid'?.text()
            }
        }else{
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code: 'api.error.invalid.request',
                    args: ['Expected content of type text/xml or text/json, content was of type: ' + request.format]])
        }
        if (!serverUUID) {
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.invalid.request', args: ['Expected server.uuid in request.']])
        }

        def reclaimMap=scheduledExecutionService.reclaimAndScheduleJobs(serverUUID)
        def successCount=reclaimMap.findAll {it.value}.size()
        def failedCount = reclaimMap.size() - successCount
        //TODO: retry for failed reclaims?

        def jobData = { entry ->
            [id: entry.key, href: g.createLink(action: 'show', controller: 'scheduledExecution',
                    id: entry.key, absolute: true)]
        }
        def jobLink={ delegate, entry->
            delegate.'job'(jobData(entry))
        }
        def successMessage= "Schedule Takeover successful for ${successCount}/${reclaimMap.size()} Jobs."
        withFormat {
            xml{
                return apiService.renderSuccessXml(response) {
                    delegate.'message'(successMessage)
                    delegate.'self'{
                        delegate.'server'(uuid:frameworkService.getServerUUID())
                    }
                    delegate.'takeoverSchedule'{
                        delegate.'server'(uuid: serverUUID)
                        delegate.'jobs'(total: reclaimMap.size()){
                            delegate.'successful'(count: successCount) {
                                reclaimMap.findAll { it.value }.each(jobLink.curry(delegate))
                            }
                            delegate.'failed'(count: failedCount) {
                                reclaimMap.findAll { !it.value }.each(jobLink.curry(delegate))
                            }
                        }
                    }
                }
            }
            json{
                render(contentType: "text/json",text: [
                    success:true,
                    apiversion:ApiRequestFilters.API_CURRENT_VERSION,
                    message: successMessage,
                    self:[server:[uuid:frameworkService.getServerUUID()]],
                    takeoverSchedule:[
                        server:[uuid: serverUUID],
                        jobs:[
                            total:reclaimMap.size(),
                            successful:reclaimMap.findAll { it.value }.collect (jobData),
                            failed:reclaimMap.findAll { !it.value }.collect (jobData)
                        ]
                    ]
                ] as JSON)
            }
        }
    }
}

class JobXMLException extends Exception{

    public JobXMLException() {
        super();
    }

    public JobXMLException(String s) {
        super(s);
    }

    public JobXMLException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public JobXMLException(Throwable throwable) {
        super(throwable);
    }

}
