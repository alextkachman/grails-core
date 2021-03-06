package org.codehaus.groovy.grails.web.binding

import java.text.SimpleDateFormat

import org.codehaus.groovy.grails.web.servlet.mvc.AbstractGrailsControllerTests

/**
 * Tests Grails data binding capabilities.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
class DataBindingTests extends AbstractGrailsControllerTests {

    protected void onSetUp() {
        gcl.parseClass('''
import grails.persistence.*

class TestController {
    def index = {}
}

@Entity
class Book {
    String title
    Author author
    URL site
}

@Entity
class MyBean {
  Integer someIntProperty
  Integer someOtherIntProperty
  Integer thirdIntProperty
  static constraints = {
    someIntProperty(min:1, nullable:true)
    someOtherIntProperty(max:99)
    thirdIntProperty nullable:false
  }
}
@Entity
class Author {
    String name
    String hairColour
    City placeOfBirth

    static constraints = {
        name(nullable:true)
    }
}
@Entity
class City {
    String name
}
        ''')
    }

    void testNamedBinding() {
        def c = ga.getControllerClass('TestController').newInstance()
        def author = ga.getDomainClass('Author').newInstance()

        def params = c.params
        params.name = 'Douglas Adams'
        params.hairColour = 'Grey'

        author.properties['name'] = params
        assertEquals 'Douglas Adams', author.name
        assertNull author.hairColour
    }

    void testNamedBindingWithMultipleProperties() {
        def c = ga.getControllerClass('TestController').newInstance()
        def author = ga.getDomainClass('Author').newInstance()

        def params = c.params
        params.name = 'Douglas Adams'
        params.hairColour = 'Grey'

        author.properties['name', 'hairColour'] = params
        assertEquals 'Douglas Adams', author.name
        assertEquals 'Grey', author.hairColour
    }

    void testThreeLevelDataBinding() {
        def c = ga.getControllerClass("TestController").newInstance()
        def b = ga.getDomainClass("Book").newInstance()

        def params = c.params
        params.title = "The Stand"
        params.'author.placeOfBirth.name' = 'Maine'
        params.'author.name' = "Stephen King"

        b.properties = params

        assertEquals "The Stand",b.title
        assertEquals "Maine", b.author.placeOfBirth.name
        assertEquals "Stephen King", b.author.name
    }

    void testBindBlankToNullWhenNullable() {
        def c = ga.getControllerClass("TestController").newInstance()
        def a = ga.getDomainClass("Author").newInstance()

        def params = c.params
        params.name =  ''
        params.hairColour = ''

        a.properties = params

        assertNull a.name
        assertEquals '', a.hairColour
    }

    void testTypeConversionErrorsWithNestedAssociations() {
        def c = ga.getControllerClass("TestController").newInstance()

        request.addParameter("author.name", "Stephen King")
        request.addParameter("author.hairColour", "Black")

        def params = c.params

        def b = ga.getDomainClass("Book").newInstance()

        b.properties = params

        def a = b.author

        assert !a.hasErrors()
        assert !b.hasErrors()
    }

    void testTypeConversionErrors() {
        def c = ga.getControllerClass("TestController").newInstance()

        request.addParameter("site", "not_a_valid_URL")

        def params = c.params

        def b = ga.getDomainClass("Book").newInstance()

        b.properties = params

        assert b.hasErrors()

        def error = b.errors.getFieldError('site')
    }

    void testValidationAfterBindingFails() {
        def c = ga.getControllerClass("TestController").newInstance()

        // binding should fail for this one
        request.addParameter("someIntProperty", "foo")

        // validation should fail for this one...
        request.addParameter("someOtherIntProperty", "999")

        // binding should fail for this one...
        request.addParameter("thirdIntProperty", "bar")

        def params = c.params

        def myBean = ga.getDomainClass("MyBean").newInstance()

        myBean.properties = params

        assertEquals "wrong number of errors before validation", 2, myBean.errors.errorCount
        assertFalse 'validation should have failed', myBean.validate()
        assertEquals 'wrong number of errors after validation', 4, myBean.errors.errorCount
    }

    void testAssociationAutoCreation() {
        def c = ga.getControllerClass("TestController").newInstance()

        request.addParameter("title", "The Stand")
        request.addParameter("author.name", "Stephen King")

        def params = c.params

        assertEquals "The Stand", params.title

        def b = ga.getDomainClass("Book").newInstance()

        b.properties = params
        assertEquals "The Stand", b.title
        assertEquals "Stephen King", b.author?.name
    }

    void testNullAssociations() {
        def c = ga.getControllerClass("TestController").newInstance()

        request.addParameter("title", "The Stand")
        request.addParameter("author.id", "null")


        def params = c.params
        def b = ga.getDomainClass("Book").newInstance()

        b.properties = params
        assertEquals "Wrong 'title' property", "The Stand", b.title
        assertNull "Expected null for property 'author'", b.author
    }

    void testAssociationsBinding() {
        def c = ga.getControllerClass("TestController").newInstance()

        def authorClass = ga.getDomainClass("Author").getClazz()
        authorClass.metaClass.'static'.get = { id ->
            def result = authorClass.newInstance()
            result.id = id
            result.name = "Mocked ${id}"
            result
        }

        request.addParameter("title", "The Stand")
        request.addParameter("author.id", "5")

        def params = c.params

        def b = ga.getDomainClass("Book").newInstance()

        b.properties = params

        assertEquals "Wrong 'title' property", "The Stand", b.title
        assertNotNull "Association 'author' should be binded", b.author
        assertEquals 5, b.author.id
        assertEquals "Mocked 5", b.author.name
    }

    void testMultiDBinding() {
        def c = ga.getControllerClass("TestController").newInstance()

        request.addParameter("author.name", "Stephen King")
        request.addParameter("author.hairColour", "Black")
        request.addParameter("title", "The Stand")
        def params = c.params

        def a = ga.getDomainClass("Author").newInstance()

        assertEquals "Stephen King",params['author'].name
        a.properties = params['author']
        assertEquals "Stephen King", a.name
        assertEquals "Black", a.hairColour

        def b = ga.getDomainClass("Book").newInstance()
        b.properties = params
        assertEquals "The Stand", b.title
        assertEquals "Stephen King", b.author?.name
    }
}
