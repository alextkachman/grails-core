/*
 * Copyright 2004-2005 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.plugins.web

import grails.util.Environment
import grails.util.GrailsUtil

import java.lang.reflect.Modifier

import org.codehaus.groovy.grails.commons.GrailsClassUtils as GCU
import org.codehaus.groovy.grails.commons.*
import org.codehaus.groovy.grails.plugins.GrailsPluginManager
import org.codehaus.groovy.grails.web.binding.DataBindingLazyMetaPropertyMap
import org.codehaus.groovy.grails.web.binding.DataBindingUtils
import org.codehaus.groovy.grails.web.errors.GrailsExceptionResolver
import org.codehaus.groovy.grails.web.filters.HiddenHttpMethodFilter
import org.codehaus.groovy.grails.web.metaclass.BindDynamicMethod
import org.codehaus.groovy.grails.web.metaclass.ChainMethod
import org.codehaus.groovy.grails.web.metaclass.ForwardMethod
import org.codehaus.groovy.grails.web.metaclass.RedirectDynamicMethod
import org.codehaus.groovy.grails.web.metaclass.RenderDynamicMethod
import org.codehaus.groovy.grails.web.metaclass.WithFormMethod
import org.codehaus.groovy.grails.web.multipart.ContentLengthAwareCommonsMultipartResolver
import org.codehaus.groovy.grails.web.plugins.support.WebMetaUtils
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes
import org.codehaus.groovy.grails.web.servlet.GrailsControllerHandlerMapping
import org.codehaus.groovy.grails.web.servlet.filter.GrailsReloadServletFilter
import org.codehaus.groovy.grails.web.servlet.mvc.CommandObjectEnablingPostProcessor
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequestFilter
import org.codehaus.groovy.grails.web.servlet.mvc.SimpleGrailsController

import org.springframework.beans.BeanUtils
import org.springframework.context.ApplicationContext
import org.springframework.validation.Errors
import org.springframework.web.context.request.RequestContextHolder as RCH
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter
import org.springframework.web.servlet.mvc.annotation.DefaultAnnotationHandlerMapping
import org.springframework.web.servlet.mvc.annotation.AnnotationMethodHandlerAdapter
import org.springframework.web.servlet.view.DefaultRequestToViewNameTranslator

/**
 * Handles the configuration of controllers for Grails.
 *
 * @author Graeme Rocher
 * @since 0.4
 */
class ControllersGrailsPlugin {

    def watchedResources = ["file:./grails-app/controllers/**/*Controller.groovy",
                            "file:./plugins/*/grails-app/controllers/**/*Controller.groovy"]

    def version = GrailsUtil.getGrailsVersion()
    def dependsOn = [core: version, i18n: version, urlMappings: version]

    def doWithSpring = {
        simpleControllerHandlerAdapter(SimpleControllerHandlerAdapter)

        exceptionHandler(GrailsExceptionResolver) {
            exceptionMappings = ['java.lang.Exception': '/error']
        }

        if (!application.config.grails.disableCommonsMultipart) {
            multipartResolver(ContentLengthAwareCommonsMultipartResolver)
        }

        mainSimpleController(SimpleGrailsController) {
            grailsApplication = ref("grailsApplication", true)
        }

        def handlerInterceptors = springConfig.containsBean("localeChangeInterceptor") ? [ref("localeChangeInterceptor")] : []
        def interceptorsClosure = {
            interceptors = handlerInterceptors
        }
        // allow @Controller annotated beans
        annotationHandlerMapping(DefaultAnnotationHandlerMapping, interceptorsClosure)
        // allow default controller mappings
        controllerHandlerMappings(GrailsControllerHandlerMapping, interceptorsClosure)

        annotationHandlerAdapter(AnnotationMethodHandlerAdapter)

        viewNameTranslator(DefaultRequestToViewNameTranslator) {
            stripLeadingSlash = false
        }

        for (controller in application.controllerClasses) {
            log.debug "Configuring controller $controller.fullName"
            if (controller.available) {
                "${controller.fullName}"(controller.clazz) { bean ->
                    bean.scope = "prototype"
                    bean.autowire = "byName"
                }
            }
        }
    }

    def doWithWebDescriptor = { webXml ->

        def basedir = System.getProperty("base.dir")
        def grailsEnv = Environment.current.name

        def mappingElement = webXml.'servlet-mapping'
        mappingElement = mappingElement[mappingElement.size() - 1]

        mappingElement + {
            'servlet-mapping' {
                'servlet-name'("grails")
                'url-pattern'("*.dispatch")
            }
        }

        def filters = webXml.filter
        def filterMappings = webXml.'filter-mapping'

        def lastFilter = filters[filters.size() - 1]
        def lastFilterMapping = filterMappings[filterMappings.size() - 1]
        def charEncodingFilterMapping = filterMappings.find {it.'filter-name'.text() == 'charEncodingFilter'}

        // add the Grails web request filter
        lastFilter + {
            filter {
                'filter-name'('hiddenHttpMethod')
                'filter-class'(HiddenHttpMethodFilter.name)
            }

            filter {
                'filter-name'('grailsWebRequest')
                'filter-class'(GrailsWebRequestFilter.name)
            }
            if (grailsEnv == "development") {
                filter {
                    'filter-name'('reloadFilter')
                    'filter-class'(GrailsReloadServletFilter.name)
                }
            }
        }

        def grailsWebRequestFilter = {
            'filter-mapping' {
                'filter-name'('hiddenHttpMethod')
                'url-pattern'("/*")
                'dispatcher'("FORWARD")
                'dispatcher'("REQUEST")
            }
            'filter-mapping' {
                'filter-name'('grailsWebRequest')
                'url-pattern'("/*")
                'dispatcher'("FORWARD")
                'dispatcher'("REQUEST")
            }
            if (grailsEnv == "development") {
                // Install the reload filter, which allows you to make
                // changes to artefacts and views while the app is running.
                //
                // All URLs are filtered, including any images, JS, CSS,
                // etc. Fortunately the reload filter now has much less
                // of an impact on response times.
                'filter-mapping' {
                    'filter-name'('reloadFilter')
                    'url-pattern'("/*")
                    'dispatcher'("FORWARD")
                    'dispatcher'("REQUEST")
                }
            }
        }

        if (charEncodingFilterMapping) {
            charEncodingFilterMapping + grailsWebRequestFilter
        }
        else {
            lastFilterMapping + grailsWebRequestFilter
        }
    }

    def doWithDynamicMethods = {ApplicationContext ctx ->

        ctx.getAutowireCapableBeanFactory().addBeanPostProcessor(new CommandObjectEnablingPostProcessor(ctx))

        // add common objects and out variable for tag libraries
        def registry = GroovySystem.getMetaClassRegistry()
        GrailsPluginManager pluginManager = getManager()

        for (domainClass in application.domainClasses) {
            GrailsDomainClass dc = domainClass
            def mc = domainClass.metaClass

            mc.constructor = { Map map ->
                def instance = ctx.containsBean(dc.fullName) ? ctx.getBean(dc.fullName) : BeanUtils.instantiateClass(dc.clazz)
                DataBindingUtils.bindObjectToDomainInstance(dc,instance, map)
                DataBindingUtils.assignBidirectionalAssociations(instance,map,dc)
                return instance
            }
            mc.setProperties = {Object o ->
                DataBindingUtils.bindObjectToDomainInstance(dc, delegate, o)
            }
            mc.getProperties = { ->
                new DataBindingLazyMetaPropertyMap(delegate)
            }
        }

        // add commons objects and dynamic methods like render and redirect to controllers
        for (GrailsClass controller in application.controllerClasses) {
            MetaClass mc = controller.metaClass

            Class controllerClass = controller.clazz
            WebMetaUtils.registerCommonWebProperties(mc, application)
            registerControllerMethods(mc, ctx)
            Class superClass = controller.clazz.superclass

            mc.getPluginContextPath = { ->
                pluginManager.getPluginPathForInstance(delegate) ?: ''
            }

            // deal with abstract super classes
            while (superClass != Object) {
                if (Modifier.isAbstract(superClass.getModifiers())) {
                    WebMetaUtils.registerCommonWebProperties(superClass.metaClass, application)
                    registerControllerMethods(superClass.metaClass, ctx)
                }
                superClass = superClass.superclass
            }

            mc.constructor = { ->
                ctx.getBean(controllerClass.name)
            }
        }
    }

    def registerControllerMethods(MetaClass mc, ApplicationContext ctx) {
        mc.getActionUri = { -> "/$controllerName/$actionName".toString()}
        mc.getControllerUri = { -> "/$controllerName".toString()}
        mc.getTemplateUri = {String name ->
            def webRequest = RCH.currentRequestAttributes()
            webRequest.attributes.getTemplateUri(name, webRequest.currentRequest)
        }
        mc.getViewUri = { String name ->
            def webRequest = RCH.currentRequestAttributes()
            webRequest.attributes.getViewUri(name, webRequest.currentRequest)
        }
        mc.setErrors = { Errors errors ->
            RCH.currentRequestAttributes().setAttribute(GrailsApplicationAttributes.ERRORS, errors, 0)
        }
        mc.getErrors = { ->
            RCH.currentRequestAttributes().getAttribute(GrailsApplicationAttributes.ERRORS, 0)
        }
        mc.setModelAndView = { ModelAndView mav ->
            RCH.currentRequestAttributes().setAttribute(GrailsApplicationAttributes.MODEL_AND_VIEW, mav, 0)
        }
        mc.getModelAndView = { ->
            RCH.currentRequestAttributes().getAttribute(GrailsApplicationAttributes.MODEL_AND_VIEW, 0)
        }
        mc.getChainModel = { ->
            RCH.currentRequestAttributes().flashScope["chainModel"]
        }
        mc.hasErrors = { ->
            errors?.hasErrors() ? true : false
        }

        def redirect = new RedirectDynamicMethod(ctx)
        def render = new RenderDynamicMethod()
        def bind = new BindDynamicMethod()
        // the redirect dynamic method
        mc.redirect = {Map args ->
            redirect.invoke(delegate, "redirect", args)
        }
        mc.chain = {Map args ->
            ChainMethod.invoke delegate, args
        }
        // the render method
        mc.render = {Object o ->
            render.invoke(delegate, "render", [o?.inspect()] as Object[])
        }

        mc.render = {String txt ->
            render.invoke(delegate, "render", [txt] as Object[])
        }
        mc.render = {Map args ->
            render.invoke(delegate, "render", [args] as Object[])
        }
        mc.render = {Closure c ->
            render.invoke(delegate, "render", [c] as Object[])
        }
        mc.render = {Map args, Closure c ->
            render.invoke(delegate, "render", [args, c] as Object[])
        }
        // the bindData method
        mc.bindData = {Object target, Object args ->
            bind.invoke(delegate, "bindData", [target, args] as Object[])
        }
        mc.bindData = {Object target, Object args, List disallowed ->
            bind.invoke(delegate, "bindData", [target, args, [exclude: disallowed]] as Object[])
        }
        mc.bindData = {Object target, Object args, List disallowed, String filter ->
            bind.invoke(delegate, "bindData", [target, args, [exclude: disallowed], filter] as Object[])
        }
        mc.bindData = {Object target, Object args, Map includeExclude ->
            bind.invoke(delegate, "bindData", [target, args, includeExclude] as Object[])
        }
        mc.bindData = {Object target, Object args, Map includeExclude, String filter ->
            bind.invoke(delegate, "bindData", [target, args, includeExclude, filter] as Object[])
        }
        mc.bindData = {Object target, Object args, String filter ->
            bind.invoke(delegate, "bindData", [target, args, filter] as Object[])
        }

        // the withForm method
        def withFormMethod = new WithFormMethod()
        mc.withForm = { Closure callable ->
            withFormMethod.withForm(delegate.request, callable)
        }

        def forwardMethod = new ForwardMethod(ctx.getBean("grailsUrlMappingsHolder"))
        mc.forward = { Map params ->
            forwardMethod.forward(delegate.request,delegate.response, params)
        }
    }

    def onChange = {event ->
        if (application.isArtefactOfType(ControllerArtefactHandler.TYPE, event.source)) {
            def context = event.ctx
            if (!context) {
                if (log.isDebugEnabled()) {
                    log.debug("Application context not found. Can't reload")
                }

                return
            }

            def controllerClass = application.addArtefact(ControllerArtefactHandler.TYPE, event.source)
            def beanDefinitions = beans {
                "${controllerClass.fullName}"(controllerClass.clazz) { bean ->
                    bean.scope = "prototype"
                    bean.autowire = true
                }
            }
            // now that we have a BeanBuilder calling registerBeans and passing the app ctx will
            // register the necessary beans with the given app ctx
            beanDefinitions.registerBeans(event.ctx)

            // Add the dynamic methods back to the class (since it's
            // effectively a completely new class).
            event.manager?.getGrailsPlugin("controllers")?.doWithDynamicMethods(event.ctx)
        }
    }
}
