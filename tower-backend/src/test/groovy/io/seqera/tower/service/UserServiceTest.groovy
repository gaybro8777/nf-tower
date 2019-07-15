/*
 * Copyright (c) 2019, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.tower.service

import javax.inject.Inject
import javax.mail.Message
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart
import javax.validation.ValidationException
import org.grails.datastore.mapping.validation.ValidationException as GrailsValidationException
import java.time.Instant

import grails.gorm.transactions.TransactionService
import grails.gorm.transactions.Transactional
import io.micronaut.test.annotation.MicronautTest
import io.seqera.mail.MailerConfig
import io.seqera.tower.Application
import io.seqera.tower.domain.AccessToken
import io.seqera.tower.domain.User
import io.seqera.tower.domain.UserRole
import io.seqera.tower.exceptions.NonExistingUserException
import io.seqera.tower.util.AbstractContainerBaseTest
import io.seqera.tower.util.DomainCreator
import org.subethamail.wiser.Wiser

@MicronautTest(application = Application.class)
@Transactional
class UserServiceTest extends AbstractContainerBaseTest {

    @Inject
    UserService userService

    @Inject
    MailerConfig mailerConfig

    @Inject
    AccessTokenService accessTokenService

    @Inject
    TransactionService tx

    Wiser smtpServer

    void setup() {
        smtpServer = new Wiser(mailerConfig.smtp.port)
        smtpServer.start()
    }

    void cleanup() {
        smtpServer.stop()
    }

    void "register a new user"() {
        given: "an email"
        String email = 'tomas@seqera.io'

        when: "register the user"
        User user
        User.withNewTransaction {
            user = userService.register(email)
        }

        then: "the user has been created"
        user.id
        user.email == email
        user.userName == email.replaceAll(/@.*/, '')
        user.authToken
        User.withNewTransaction {
            User.count() == 1
        }

        and:
        user.accessTokens.size() == 1
        AccessToken.withNewTransaction {
            AccessToken.count() == 1
        }

        and: "a role was attached to the user"
        UserRole.list().first().user.id == user.id
        UserRole.list().first().role.authority == 'ROLE_USER'

        and: "the access link was sent to the user"
        smtpServer.messages.size() == 1
        Message message = smtpServer.messages.first().mimeMessage
        message.allRecipients.contains(new InternetAddress(user.email))
        message.subject == 'Nextflow Tower Sign in'
        (message.content as MimeMultipart).getBodyPart(0).content.getBodyPart(0).content.contains('Hi tomas,')
    }

    void "register a user already registered"() {
        given: "an existing user"
        User existingUser = new DomainCreator().createUser()

        and: 'save the authToken and authTime for a later check'
        String authToken = existingUser.authToken
        Instant authTime = existingUser.authTime

        when: "register a user with the same email of the previous one"
        User userToRegister
        User.withNewTransaction {
            userToRegister = userService.register(existingUser.email)
        }
        String userName = userToRegister.userName

        then: "the returned user is the same as the previous one"
        userToRegister.id == existingUser.id
        userToRegister.email == existingUser.email
        User.withNewTransaction {
            User.count() == 1
        }

        and: 'the auth token has been refreshed'
        userToRegister.authToken != authToken
        userToRegister.authTime > authTime

        and: 'the access email has been sent'
        smtpServer.messages.size() == 1
        Message message = smtpServer.messages.first().mimeMessage
        message.allRecipients.contains(new InternetAddress(existingUser.email))
        message.subject == 'Nextflow Tower Sign in'
        (message.content as MimeMultipart).getBodyPart(0).content.getBodyPart(0).content.contains("Hi $userName,")
    }

    void "register a new user and then a user with a similar email"() {
        when: "register a user"
        String email = 'user@seqera.io'
        User user
        User.withNewTransaction {
            user = userService.register('user@seqera.io')
        }

        then: "the user has been created"
        user.id
        user.email == email
        user.userName == email.replaceAll(/@.*/, '')
        user.authToken
        User.withNewTransaction {
            User.count() == 1
        }

        when: "register a user with a similar email to the first one"
        String email2 = 'user@email.com'
        User user2
        User.withNewTransaction {
            user2 = userService.register(email2)
        }

        then: "the user has been created and the userName has an appended number"
        user2.id
        user2.email == email2
        user2.userName == 'user1'
        user2.authToken
        User.withNewTransaction {
            User.count() == 2
        }
    }

    void "try to register a user given an invalid email"() {
        given: "an invalid email"
        String badEmail = 'badEmail'

        when: "register a user with a bad email"
        userService.register(badEmail)

        then: "the user couldn't be created"
        ValidationException e = thrown(ValidationException)
        e.message == "Can't save a user with bad email format"
        User.withNewTransaction {
            User.count() == 0
        }
    }

    void "update an existing user given new user data"() {
        given: 'an existing user'
        User user = new DomainCreator().createUser()

        and: 'some new data encapsulated in a user object'
        User userData = new DomainCreator(save: false).createUser(userName: 'user', firstName: 'User', lastName: 'Userson', avatar: 'https://i.pravatar.cc/200', organization: 'Org', description: 'Desc')

        when: 'update the user'
        User updatedUser
        User.withNewTransaction {
            updatedUser = userService.update(user, userData)
        }

        then: "the user has been correctly updated"
        updatedUser.userName == userData.userName
        updatedUser.firstName == userData.firstName
        updatedUser.lastName == userData.lastName
        updatedUser.avatar == userData.avatar
        updatedUser.organization == userData.organization
        updatedUser.description == userData.description
        User.withNewTransaction {
            User.count() == 1
        }
    }

    void "try to update an existing user, but with some invalid data"() {
        given: 'an existing user'
        User user = new DomainCreator().createUser()

        and: 'some new data encapsulated in a user object'
        User userData = new DomainCreator(save: false).createUser(avatar: 'badUrl')

        when: 'update the user'
        User.withNewTransaction {
            userService.update(user, userData)
        }

        then: "a validation exception is thrown"
        ValidationException e = thrown(ValidationException)
        e.message == "Can't save a user with bad avatar URL format"
    }

    void "try to update a non existing user"() {
        given: 'some new data encapsulated in a user object'
        User userData = new DomainCreator(save: false).createUser(avatar: 'badUrl')

        when: 'update a non existing user'
        User.withNewTransaction {
            userService.update(null, userData)
        }

        then: "a non-existing exception is thrown"
        NonExistingUserException e = thrown(NonExistingUserException)
        e.message == "The user to update doesn't exist"
    }

    void "delete an existing user without roles"() {
        given: 'an existing user'
        User user = new DomainCreator().createUser()

        when: 'remove the user'
        User.withNewTransaction {
            userService.delete(user)
        }

        then: "the user has been correctly deleted"
        User.withNewTransaction {
            User.count() == 0
        }
    }

    void "delete an existing user with roles and associated workflows"() {
        given: 'an existing user'
        User user = new DomainCreator().createUser()

        and: "grant a role to the user"
        new DomainCreator().createUserRole(user: user)

        and: 'associate some workflows to the user'
        3.times {
            new DomainCreator().createWorkflow(owner: user)
        }

        when: 'remove the user'
        User.withNewTransaction {
            userService.delete(user)
        }

        then: "the user has been correctly deleted"
        User.withNewTransaction {
            User.count() == 0
        }
    }

    void "try to delete an nonexistent user"() {
        when: 'remove a non existing user'
        User.withNewTransaction {
            userService.delete(null)
        }

        then: "a non-existing exception is thrown"
        NonExistingUserException e = thrown(NonExistingUserException)
        e.message == "The user to delete doesn't exist"
    }


    def 'should find a user by the access token' () {
        given:
        User user = tx.withNewTransaction { new DomainCreator().createUser() }
        def tokens = accessTokenService.findByUser(user)

        expect:
        tokens.size()==1

        when:
        def expected = userService.getByAccessToken(tokens.get(0).token)
        then:
        user.id == expected.id
    }

    def 'user name should not start with number' () {
        when: 'starts with a number'
        tx.withNewTransaction { new DomainCreator().createUser(userName: '0abc') }
        then:
        notThrown(GrailsValidationException)

        when: 'contains more than one -'
        tx.withNewTransaction { new DomainCreator().createUser(userName: 'a----b') }
        then:
        thrown(GrailsValidationException)

        when: 'contains blanks'
        tx.withNewTransaction { new DomainCreator().createUser(userName: 'a b') }
        then:
        thrown(GrailsValidationException)

        when: 'uppercase is used'
        tx.withNewTransaction { new DomainCreator().createUser(userName: 'ABC') }
        then:
        thrown(GrailsValidationException)

        when: 'underscore is used'
        tx.withNewTransaction { new DomainCreator().createUser(userName: 'a_b') }
        then:
        thrown(GrailsValidationException)

        when: 'starts with -'
        tx.withNewTransaction { new DomainCreator().createUser(userName: '-aa') }
        then:
        thrown(GrailsValidationException)

        when: 'ends with -'
        tx.withNewTransaction { new DomainCreator().createUser(userName: 'aa-') }
        then:
        thrown(GrailsValidationException)
    }

}