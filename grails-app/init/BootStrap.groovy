/*
* OpenSpeedMonitor (OSM)
* Copyright 2014 iteratec GmbH
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* 	http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/


import de.iteratec.osm.OsmConfiguration
import de.iteratec.osm.api.MicroServiceApiKey
import de.iteratec.osm.batch.BatchActivity
import de.iteratec.osm.batch.Status
import de.iteratec.osm.csi.*
import de.iteratec.osm.measurement.environment.Browser
import de.iteratec.osm.measurement.environment.BrowserAlias
import de.iteratec.osm.measurement.environment.wptserverproxy.AssetRequestPersisterService
import de.iteratec.osm.measurement.environment.wptserverproxy.LocationPersisterService
import de.iteratec.osm.measurement.environment.wptserverproxy.ResultPersisterService
import de.iteratec.osm.measurement.environment.wptserverproxy.ProxyService
import de.iteratec.osm.measurement.schedule.ConnectivityProfile
import de.iteratec.osm.measurement.schedule.JobGroup

import de.iteratec.osm.measurement.schedule.JobProcessingService
import de.iteratec.osm.report.chart.AggregatorType
import de.iteratec.osm.report.chart.CsiAggregationInterval
import de.iteratec.osm.report.chart.CsiAggregationUtilService
import de.iteratec.osm.report.chart.MeasurandGroup
import de.iteratec.osm.report.external.GraphiteServer
import de.iteratec.osm.report.external.HealthReportService
import de.iteratec.osm.result.JobResultDaoService
import de.iteratec.osm.security.Role
import de.iteratec.osm.security.User
import de.iteratec.osm.security.UserRole
import de.iteratec.osm.util.I18nService
import grails.util.BuildSettings
import grails.util.Environment
import org.apache.commons.validator.routines.UrlValidator
import org.joda.time.DateTime
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

class BootStrap {

    ResourceLoader defaultResourceLoader = new DefaultResourceLoader()
    EventCsiAggregationService eventCsiAggregationService
    CsiAggregationUtilService csiAggregationUtilService
    JobProcessingService jobProcessingService
    I18nService i18nService
    ResultPersisterService resultPersisterService
    LocationPersisterService locationPersisterService
    AssetRequestPersisterService assetRequestPersisterService
    ProxyService proxyService
    HealthReportService healthReportService
    def grailsApplication

    def init = { servletContext ->

        switch (Environment.getCurrent()) {
            case Environment.DEVELOPMENT:
                initApplicationData(true)
                registerProxyListener()

                break
            case Environment.TEST:
                // no creation of test-data, cause each test will create its own data
                registerProxyListener()
                break;
            case Environment.PRODUCTION:
                initApplicationData(false)
                registerProxyListener()

                break
        }

    }

    def destroy = {
    }

    def initApplicationData = { boolean createDefaultUsers ->
        log.info "initApplicationData() OSM starts"

        initConfig()
        initUserData(createDefaultUsers)
        initChartData()
        initCsiData()
        initMeasurementInfrastructure()
        initJobScheduling()
        cancelActiveBatchActivity()
        excludePropertiesInJsonRepresentationsofDomainObjects()
        initHealthReporting()

        log.info "initApplicationData() OSM ends"
    }


    void initHealthReporting() {
        log.info("initHealthReporting() OSM starts")
        GraphiteServer.findAllByReportHealthMetrics(true).each {
            healthReportService.handleGraphiteServer(it)
        }
        log.info("initHealthReporting() OSM ends")
    }

    void initConfig() {
        log.info "initConfig() OSM starts"

        List<OsmConfiguration> configs = OsmConfiguration.list()

        if (configs.size() != 1) {
            deleteAllInvalidAndCreateNewOsmConfig(configs)
        }

        log.info "initConfig() OSM ends"
    }

    void deleteAllInvalidAndCreateNewOsmConfig(List<OsmConfiguration> configs) {
        configs.each {
            it.delete()
        }
        new OsmConfiguration(
                detailDataStorageTimeInWeeks: 12,
                defaultMaxDownloadTimeInMinutes: 60,
                minDocCompleteTimeInMillisecs: 250,
                maxDocCompleteTimeInMillisecs: 180000,
                maxDataStorageTimeInMonths: 13,
                csiTransformation: CsiTransformation.BY_MAPPING
        ).save(failOnError: true)
    }

    void initJobScheduling() {
        log.info "initJobScheduling() OSM starts"

        createConnectivityProfileIfMissing(6000, 512, 50, 'DSL 6.000', 0)
        createConnectivityProfileIfMissing(384, 384, 140, 'UMTS', 0)
        createConnectivityProfileIfMissing(3600, 1500, 40, 'UMTS - HSDPA', 0)

        jobProcessingService.scheduleAllActiveJobs()

        log.info "initJobScheduling() OSM ends"
    }

    void initUserData(boolean createDefaultUsers) {
        log.info "initUserData() OSM starts"

        // Roles ////////////////////////////////////////////////////////////////////////
        Role adminRole = Role.findByAuthority('ROLE_ADMIN') ?: new Role(authority: 'ROLE_ADMIN').save(failOnError: true)
        Role rootRole = Role.findByAuthority('ROLE_SUPER_ADMIN') ?: new Role(authority: 'ROLE_SUPER_ADMIN').save(failOnError: true)

        // Users ////////////////////////////////////////////////////////////////////////

        //read config entries
        String appAdminUserName = grailsApplication.config.grails.de.iteratec.osm.security.initialOsmAdminUser.username.isEmpty() ?
                null : grailsApplication.config.grails.de.iteratec.osm.security.initialOsmAdminUser.username
        String appAdminPassword = grailsApplication.config.grails.de.iteratec.osm.security.initialOsmAdminUser.password.isEmpty() ?
                null : grailsApplication.config.grails.de.iteratec.osm.security.initialOsmAdminUser.password
        String appRootUserName = grailsApplication.config.grails.de.iteratec.osm.security.initialOsmRootUser.username.isEmpty() ?
                null : grailsApplication.config.grails.de.iteratec.osm.security.initialOsmRootUser.username
        String appRootPassword = grailsApplication.config.grails.de.iteratec.osm.security.initialOsmRootUser.password.isEmpty() ?
                null : grailsApplication.config.grails.de.iteratec.osm.security.initialOsmRootUser.password
        String warnMessage = createDefaultUsers ? 'A default user will be created if no one existed.' : 'No such user will be created.'

        // admin user
        if (appAdminUserName == null || appAdminPassword == null) {
            log.warn("You haven't set environment variables to create an admin user. ${warnMessage}")
            if (createDefaultUsers) createUser('admin', 'admin', adminRole)
        } else {
            createUser(appAdminUserName, appAdminPassword, adminRole)
        }
        //root user
        if (appRootUserName == null || appRootPassword == null) {
            log.warn("You haven't set environment variables to create a root user. ${warnMessage}")
            if (createDefaultUsers) createUser('root', 'root', rootRole)
        } else {
            createUser(appRootUserName, appRootPassword, rootRole)
        }


        //apiKey for microService
        Boolean enablePersistenceOfAssetRequests = grailsApplication.config.grails.de.iteratec.osm.assetRequests.enablePersistenceOfAssetRequests == null ?
                null :  grailsApplication.config.grails.de.iteratec.osm.assetRequests.enablePersistenceOfAssetRequests
        if(enablePersistenceOfAssetRequests) {
            if (MicroServiceApiKey.list().isEmpty()) {
                String initialApiKey = grailsApplication.config.grails.de.iteratec.osm.security.initialApiKey.isEmpty() ?
                        null : grailsApplication.config.grails.de.iteratec.osm.security.initialApiKey
                String initialMicroServiceName = grailsApplication.config.grails.de.iteratec.osm.security.initialMicroServiceName.isEmpty() ?
                        null : grailsApplication.config.grails.de.iteratec.osm.security.initialMicroServiceName
                if (!initialApiKey || !initialMicroServiceName) log.warn("initial MicroserviceApiKey configuration missing")
                else new MicroServiceApiKey([secretKey: initialApiKey, microService: initialMicroServiceName, valid: true]).save(failOnError: true)
            }
        }

        log.info "initUserData() OSM ends"
    }

    void createUser(String username, String password, Role role) {
        User user = User.findByUsername(username) ?: new User(
                username: username,
                password: password,
                enabled: true,
                accountExpired: false,
                accountLocked: false,
                passwordExpired: false).save(failOnError: true)
        UserRole.findByUser(user) ?: new UserRole(user: user, role: role).save(failOnError: true)
    }

    void initChartData() {
        log.info "initChartData starts"

        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_DOC_COMPLETE_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_DOM_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_FIRST_BYTE, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_FULLY_LOADED_REQUEST_COUNT, MeasurandGroup.REQUEST_COUNTS);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_FULLY_LOADED_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_LOAD_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_START_RENDER, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_DOC_COMPLETE_INCOMING_BYTES, MeasurandGroup.REQUEST_SIZES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_DOC_COMPLETE_REQUESTS, MeasurandGroup.REQUEST_COUNTS);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_FULLY_LOADED_INCOMING_BYTES, MeasurandGroup.REQUEST_SIZES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_CS_BASED_ON_DOC_COMPLETE_IN_PERCENT, MeasurandGroup.PERCENTAGES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_SPEED_INDEX, MeasurandGroup.UNDEFINED);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_VISUALLY_COMPLETE, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_UNCACHED_CS_BASED_ON_VISUALLY_COMPLETE_IN_PERCENT, MeasurandGroup.PERCENTAGES);

        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_DOC_COMPLETE_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_DOM_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_FIRST_BYTE, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_FULLY_LOADED_REQUEST_COUNT, MeasurandGroup.REQUEST_COUNTS);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_FULLY_LOADED_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_LOAD_TIME, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_START_RENDER, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_DOC_COMPLETE_INCOMING_BYTES, MeasurandGroup.REQUEST_SIZES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_DOC_COMPLETE_REQUESTS, MeasurandGroup.REQUEST_COUNTS);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_FULLY_LOADED_INCOMING_BYTES, MeasurandGroup.REQUEST_SIZES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_CS_BASED_ON_DOC_COMPLETE_IN_PERCENT, MeasurandGroup.PERCENTAGES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_SPEED_INDEX, MeasurandGroup.UNDEFINED);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_VISUALLY_COMPLETE, MeasurandGroup.LOAD_TIMES);
        createAggregatorTypeIfMissing(AggregatorType.RESULT_CACHED_CS_BASED_ON_VISUALLY_COMPLETE_IN_PERCENT, MeasurandGroup.PERCENTAGES);

        log.info "initChartData ends"
    }

    void createAggregatorTypeIfMissing(String aggregatorName, MeasurandGroup groupName) {
        // Add aggregator Types if missing
        AggregatorType.findByName(aggregatorName) ?: new AggregatorType([name: aggregatorName, measurandGroup: groupName]).save(failOnError: true)
    }


    void initCsiData() {
        log.info "initCsiData starts"

        def csiGroupName = JobGroup.UNDEFINED_CSI
        JobGroup.findByName(csiGroupName) ?: new JobGroup(
                name: csiGroupName).save(failOnError: true)

        // here you can initialize the weights of the hours of the csiDay for csi calculation  (see de.iteratec.osm.csi.PageCsiAggregationService)
        if (CsiDay.count <= 0) {
            CsiDay initDay = new CsiDay()
            (0..23).each {
                initDay.setHourWeight(it, 1)
            }
            initDay.save(failOnError: true)
        }

        Page.findByName(Page.UNDEFINED) ?: new Page(name: Page.UNDEFINED).save(failOnError: true)

        createAggregatorTypeIfMissing(AggregatorType.MEASURED_EVENT, MeasurandGroup.NO_MEASURAND)
        createAggregatorTypeIfMissing(AggregatorType.PAGE, MeasurandGroup.NO_MEASURAND)
        createAggregatorTypeIfMissing(AggregatorType.PAGE_AND_BROWSER, MeasurandGroup.NO_MEASURAND)
        createAggregatorTypeIfMissing(AggregatorType.SHOP, MeasurandGroup.NO_MEASURAND)
        createAggregatorTypeIfMissing(AggregatorType.CSI_SYSTEM, MeasurandGroup.NO_MEASURAND)

        CsiAggregationInterval.findByIntervalInMinutes(CsiAggregationInterval.HOURLY) ?: new CsiAggregationInterval(
                name: "hourly",
                intervalInMinutes: CsiAggregationInterval.HOURLY
        ).save(failOnError: true)

        CsiAggregationInterval.findByIntervalInMinutes(CsiAggregationInterval.DAILY) ?: new CsiAggregationInterval(
                name: "daily",
                intervalInMinutes: CsiAggregationInterval.DAILY
        ).save(failOnError: true)

        CsiAggregationInterval.findByIntervalInMinutes(CsiAggregationInterval.WEEKLY) ?: new CsiAggregationInterval(
                name: "weekly",
                intervalInMinutes: CsiAggregationInterval.WEEKLY
        ).save(failOnError: true)

        Date date = new DateTime(2000, 1, 1, 0, 0).toDate()
        Double percent = 90
        CsTargetValue val1 = CsTargetValue.findByDateAndCsInPercent(date, percent) ?: new CsTargetValue(
                date: date,
                csInPercent: percent,
        ).save(failOnError: true)
        date = new DateTime(2100, 12, 31, 23, 59).toDate()
        percent = 90
        CsTargetValue val2 = CsTargetValue.findByDateAndCsInPercent(date, percent) ?: new CsTargetValue(
                date: date,
                csInPercent: percent,
        ).save(failOnError: true)

        String labelTargetCsi_EN = i18nService.msgInLocale('de.iteratec.isocsi.targetcsi.label', Locale.ENGLISH, 'Target-CSI')
        String descriptionTargetCsi_EN = i18nService.msgInLocale('de.iteratec.isocsi.targetcsi.description', Locale.ENGLISH, 'Customer satisfaction index defined as target.')
        CsTargetGraph.findByLabel(labelTargetCsi_EN) ?: new CsTargetGraph(
                label: labelTargetCsi_EN,
                description: descriptionTargetCsi_EN,
                pointOne: val1,
                pointTwo: val2,
                defaultVisibility: true
        ).save(failOnError: true)
        String labelTargetCsi_DE = i18nService.msgInLocale('de.iteratec.isocsi.targetcsi.label', Locale.GERMAN, 'Target-CSI')
        String descriptionTargetCsi_DE = i18nService.msgInLocale('de.iteratec.isocsi.targetcsi.description', Locale.GERMAN, 'Customer satisfaction index defined as target.')
        CsTargetGraph.findByLabel(labelTargetCsi_DE) ?: new CsTargetGraph(
                label: labelTargetCsi_DE,
                description: descriptionTargetCsi_DE,
                pointOne: val1,
                pointTwo: val2,
                defaultVisibility: true
        ).save(failOnError: true)

        createDefaultTimeToCsiMappingIfMissing()

        if (CsiConfiguration.count <= 0) {
            CsiConfiguration initCsiConfiguration = new CsiConfiguration()
            initCsiConfiguration.with {
                label = "initial csi configuration"
                description = "a first csi configuration as template"
                csiDay = CsiDay.findAll()[0]
            }
            initCsiConfiguration.save(failOnError: true)
        }

        log.info "initCsiData ends"
    }

    /**
     * These default mappings can be assigned to measured pages if no data of a real customer survey exist.
     * Get created only if no one exist at all.
     */
    void createDefaultTimeToCsiMappingIfMissing() {

        if (DefaultTimeToCsMapping.list().size() == 0) {

            Map indexToMappingName = [1: '1 - impatient', 2: '2', 3: '3', 4: '4', 5: '5 - patient']
            String pathToFile
            String fileName = 'Default_CSI_Mappings.csv'
            InputStream csvIs = this.class.classLoader.getResourceAsStream(fileName)
            BufferedReader csvFileReader = new BufferedReader(new InputStreamReader(csvIs))
            int lineCounter = 0
            String line
            while ((line = csvFileReader.readLine()) != null) {
                // exclude header
                if (lineCounter >= 1) {
                    def tokenized = line.tokenize(';')
                    5.times { defaultMappingindex ->
                        new DefaultTimeToCsMapping(
                                name: indexToMappingName[defaultMappingindex + 1],
                                loadTimeInMilliSecs: tokenized[0],
                                customerSatisfactionInPercent: tokenized[defaultMappingindex + 1]
                        ).save(failOnError: true)
                    }

                }
                lineCounter++
            }
            csvFileReader.close();
        }

    }

    void createConnectivityProfileIfMissing(Integer bwDown, Integer bwUp, Integer latency, String name, Integer packetLoss) {
        ConnectivityProfile.findByName(name) ?:
                new ConnectivityProfile(
                        active: true,
                        bandwidthDown: bwDown,
                        bandwidthUp: bwUp,
                        latency: latency,
                        name: name,
                        packetLoss: packetLoss).save(failOnError: true)
    }

    void initMeasurementInfrastructure() {

        log.info "init measurement infrastructure OSM starts"

        //undefined
        String browserName = Browser.UNDEFINED
        Browser.findByName(browserName) ?: new Browser(
                name: browserName,
                weight: 0)
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias(Browser.UNDEFINED))
                .save(failOnError: true)

        //IE
        browserName = "IE"
        Browser.findByName(browserName) ?: new Browser(
                name: browserName,
                weight: 1)
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("IE"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("IE8"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("Internet Explorer"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("Internet Explorer 8"))
                .save(failOnError: true)

        //FF
        browserName = "Firefox"
        Browser.findByName(browserName) ?: new Browser(
                name: browserName,
                weight: 1)
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("FF"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("FF7"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("Firefox"))
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("Firefox7"))
                .save(failOnError: true)

        //Chrome
        browserName = "Chrome"
        Browser.findByName(browserName) ?: new Browser(
                name: browserName,
                weight: 1)
                .addToBrowserAliases(BrowserAlias.findOrCreateByAlias("Chrome"))
                .save(failOnError: true)

        log.info "init measurement infrastructure OSM ends"

    }

    def registerProxyListener = {
        log.info "registerProxyListener OSM ends"
        proxyService.addLocationListener(locationPersisterService)
        proxyService.addResultListener(resultPersisterService)

        // enable peristence of assetRequests for JobResults if configured
        boolean persistenceEnabled = grailsApplication.config.grails.de?.iteratec?.osm?.assetRequests?.enablePersistenceOfAssetRequests?.toLowerCase() == "true"
        if (persistenceEnabled) {
            String microserviceUrl = grailsApplication.config.grails?.de?.iteratec?.osm?.assetRequests?.microserviceUrl
            UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS)
            if (!microserviceUrl || !urlValidator.isValid(microserviceUrl)) {
                throw new IllegalStateException("A valid url for the microservice is required, if persistence of assetRequests is to be enabled")
            }
            assetRequestPersisterService.enablePersistenceOfAssetRequestsForJobResults(microserviceUrl)
            proxyService.addResultListener(assetRequestPersisterService)
        }

        log.info "persistence of assetRequests is enabled: " + persistenceEnabled
        log.info "registerProxyListener OSM ends"
    }

    void cancelActiveBatchActivity() {
        BatchActivity.findAllByStatus(Status.ACTIVE).each { BatchActivity batchActivity ->
            BatchActivity.withTransaction {
                batchActivity.status = Status.CANCELLED
                batchActivity.save(faildOnError: true)
            }
        }
    }

    void excludePropertiesInJsonRepresentationsofDomainObjects() {

        ArrayList<String> propertiesToExcludeFromAllDomains = ['class', 'dirty', 'dirtyPropertyNames', 'errors', 'properties']

        grailsApplication.domainClasses*.clazz.each { domainClass ->
            grails.converters.JSON.registerObjectMarshaller(domainClass) {

                Map propertiesToRepresent = it.properties.findAll { k, v -> !propertiesToExcludeFromAllDomains.contains(k) }
                propertiesToRepresent['id'] = it.ident()

                removeAllServices(propertiesToRepresent)
                removeDomainSpecificProperties(domainClass, propertiesToRepresent)

                return propertiesToRepresent

            }
        }
    }

    void removeDomainSpecificProperties(Class domainClass, Map propertiesToRepresent) {
        if (domainClass == de.iteratec.osm.measurement.environment.BrowserAlias) propertiesToRepresent.remove('browser')
        else if (domainClass == de.iteratec.osm.measurement.schedule.JobGroup) propertiesToRepresent.remove('graphiteServers')
    }

    void removeAllServices(Map propertiesToRepresent) {
        Iterator iterator = propertiesToRepresent.keySet().iterator()
        while (iterator.hasNext()) {
            if (iterator.next().endsWith('Service')) iterator.remove()
        }
    }

}
