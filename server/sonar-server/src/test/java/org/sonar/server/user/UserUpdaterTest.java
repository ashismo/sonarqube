/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.user;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import java.util.List;
import org.elasticsearch.search.SearchHit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.Settings;
import org.sonar.api.platform.NewUserHandler;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.user.GroupTesting;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserTesting;
import org.sonar.server.es.EsTester;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ServerException;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.user.index.UserIndexDefinition;
import org.sonar.server.user.index.UserIndexer;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.api.CoreProperties.CORE_DEFAULT_GROUP;
import static org.sonar.db.user.UserTesting.newDisabledUser;
import static org.sonar.db.user.UserTesting.newUserDto;

public class UserUpdaterTest {

  private static final long NOW = 1418215735482L;
  private static final long PAST = 1000000000000L;
  private static final String DEFAULT_LOGIN = "marius";

  private System2 system2 = mock(System2.class);

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public EsTester es = new EsTester(new UserIndexDefinition(new MapSettings()));

  @Rule
  public DbTester db = DbTester.create(system2);

  private DbClient dbClient = db.getDbClient();
  private NewUserNotifier newUserNotifier = mock(NewUserNotifier.class);
  private ArgumentCaptor<NewUserHandler.Context> newUserHandler = ArgumentCaptor.forClass(NewUserHandler.Context.class);
  private Settings settings = new MapSettings();
  private DbSession session = db.getSession();
  private UserIndexer userIndexer = new UserIndexer(system2, dbClient, es.client());
  private UserUpdater underTest = new UserUpdater(newUserNotifier, settings, dbClient, userIndexer, system2, TestDefaultOrganizationProvider.from(db));

  @Before
  public void setUp() {
    when(system2.now()).thenReturn(NOW);
  }

  @Test
  public void create_user() {
    createDefaultGroup();

    UserDto dto = underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("PASSWORD")
      .setScmAccounts(ImmutableList.of("u1", "u_1", "User 1"))
      .build());

    assertThat(dto.getId()).isNotNull();
    assertThat(dto.getLogin()).isEqualTo("user");
    assertThat(dto.getName()).isEqualTo("User");
    assertThat(dto.getEmail()).isEqualTo("user@mail.com");
    assertThat(dto.getScmAccountsAsList()).containsOnly("u1", "u_1", "User 1");
    assertThat(dto.isActive()).isTrue();
    assertThat(dto.isLocal()).isTrue();

    assertThat(dto.getSalt()).isNotNull();
    assertThat(dto.getCryptedPassword()).isNotNull();
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735482L);

    assertThat(dbClient.userDao().selectByLogin(session, "user").getId()).isEqualTo(dto.getId());
    List<SearchHit> indexUsers = es.getDocuments(UserIndexDefinition.INDEX, UserIndexDefinition.TYPE_USER);
    assertThat(indexUsers).hasSize(1);
    assertThat(indexUsers.get(0).getSource())
      .contains(
        entry("login", "user"),
        entry("name", "User"),
        entry("email", "user@mail.com"));
  }

  @Test
  public void create_user_with_sq_authority_when_no_authority_set() throws Exception {
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .build());

    UserDto dto = dbClient.userDao().selectByLogin(session, "user");
    assertThat(dto.getExternalIdentity()).isEqualTo("user");
    assertThat(dto.getExternalIdentityProvider()).isEqualTo("sonarqube");
    assertThat(dto.isLocal()).isTrue();
  }

  @Test
  public void create_user_with_minimum_fields() {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("us")
      .setName("User")
      .build());

    UserDto dto = dbClient.userDao().selectByLogin(session, "us");
    assertThat(dto.getId()).isNotNull();
    assertThat(dto.getLogin()).isEqualTo("us");
    assertThat(dto.getName()).isEqualTo("User");
    assertThat(dto.getEmail()).isNull();
    assertThat(dto.getScmAccounts()).isNull();
    assertThat(dto.isActive()).isTrue();
  }

  @Test
  public void create_user_with_scm_accounts_containing_blank_or_null_entries() {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "", null))
      .build());

    assertThat(dbClient.userDao().selectByLogin(session, "user").getScmAccountsAsList()).containsOnly("u1");
  }

  @Test
  public void create_user_with_scm_accounts_containing_one_blank_entry() {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .setScmAccounts(newArrayList(""))
      .build());

    assertThat(dbClient.userDao().selectByLogin(session, "user").getScmAccounts()).isNull();
  }

  @Test
  public void create_user_with_scm_accounts_containing_duplications() {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "u1"))
      .build());

    assertThat(dbClient.userDao().selectByLogin(session, "user").getScmAccountsAsList()).containsOnly("u1");
  }

  @Test
  public void fail_to_create_user_with_missing_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login can't be empty");

    underTest.create(NewUser.builder()
      .setLogin(null)
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_invalid_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Use only letters, numbers, and .-_@ please.");

    underTest.create(NewUser.builder()
      .setLogin("/marius/")
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_space_in_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Use only letters, numbers, and .-_@ please.");

    underTest.create(NewUser.builder()
      .setLogin("mari us")
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_too_short_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login is too short (minimum is 2 characters)");

    underTest.create(NewUser.builder()
      .setLogin("m")
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_too_long_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login is too long (maximum is 255 characters)");

    underTest.create(NewUser.builder()
      .setLogin(Strings.repeat("m", 256))
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_missing_name() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Name can't be empty");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName(null)
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_too_long_name() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Name is too long (maximum is 200 characters)");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName(Strings.repeat("m", 201))
      .setEmail("marius@mail.com")
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_too_long_email() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Email is too long (maximum is 100 characters)");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius")
      .setEmail(Strings.repeat("m", 101))
      .setPassword("password")
      .build());
  }

  @Test
  public void fail_to_create_user_with_many_errors() {
    try {
      underTest.create(NewUser.builder()
        .setLogin("")
        .setName("")
        .setEmail("marius@mail.com")
        .setPassword("")
        .build());
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).hasSize(3);
    }
  }

  @Test
  public void fail_to_create_user_when_scm_account_is_already_used() {
    db.prepareDbUnit(getClass(), "fail_to_create_user_when_scm_account_is_already_used.xml");
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The scm account 'jo' is already used by user(s) : 'John (john)'");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("jo"))
      .build());
  }

  @Test
  public void fail_to_create_user_when_scm_account_is_already_used_by_many_user() {
    db.prepareDbUnit(getClass(), "fail_to_create_user_when_scm_account_is_already_used_by_many_user.xml");
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The scm account 'john@email.com' is already used by user(s) : 'John (john), Technical account (technical-account)'");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius")
      .setEmail("marius@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("john@email.com"))
      .build());
  }

  @Test
  public void fail_to_create_user_when_scm_account_is_user_login() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login and email are automatically considered as SCM accounts");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList(DEFAULT_LOGIN))
      .build());
  }

  @Test
  public void fail_to_create_user_when_scm_account_is_user_email() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login and email are automatically considered as SCM accounts");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("marius2@mail.com"))
      .build());
  }

  @Test
  public void notify_new_user() {
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "u_1"))
      .build());

    verify(newUserNotifier).onNewUser(newUserHandler.capture());
    assertThat(newUserHandler.getValue().getLogin()).isEqualTo("user");
    assertThat(newUserHandler.getValue().getName()).isEqualTo("User");
    assertThat(newUserHandler.getValue().getEmail()).isEqualTo("user@mail.com");
  }

  @Test
  public void associate_default_group_when_creating_user() {
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "u_1"))
      .build());

    Multimap<String, String> groups = dbClient.groupMembershipDao().selectGroupsByLogins(session, asList("user"));
    assertThat(groups.get("user")).containsOnly("sonar-users");
  }

  @Test
  public void doest_not_fail_when_no_default_group() {
    settings.setProperty(CORE_DEFAULT_GROUP, (String) null);

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "u_1"))
      .build());

    assertThat(dbClient.userDao().selectByLogin(session, "user")).isNotNull();
  }

  @Test
  public void fail_to_associate_default_group_when_default_group_does_not_exist() {
    settings.setProperty(CORE_DEFAULT_GROUP, "polop");
    expectedException.expect(ServerException.class);
    expectedException.expectMessage("The default group 'polop' for new users does not exist. Please update the general security settings to fix this issue.");

    underTest.create(NewUser.builder()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setScmAccounts(newArrayList("u1", "u_1"))
      .build());
  }

  @Test
  public void reactivate_user_when_creating_user_with_existing_login() {
    addUser(newDisabledUser(DEFAULT_LOGIN)
      .setLocal(false)
      .setCreatedAt(PAST)
      .setUpdatedAt(PAST));
    createDefaultGroup();

    UserDto dto = underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .build());
    session.commit();

    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccounts()).isNull();
    assertThat(dto.isLocal()).isTrue();

    assertThat(dto.getSalt()).isNotNull().isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotNull().isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
    assertThat(dto.getCreatedAt()).isEqualTo(PAST);
    assertThat(dto.getUpdatedAt()).isEqualTo(NOW);

    assertThat(dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN).isActive()).isTrue();
  }

  @Test
  public void reactivate_user_not_having_password() {
    db.prepareDbUnit(getClass(), "reactivate_user_not_having_password.xml");
    when(system2.now()).thenReturn(1418215735486L);
    createDefaultGroup();

    UserDto dto = underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .build());
    session.commit();

    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccounts()).isNull();

    assertThat(dto.getSalt()).isNull();
    assertThat(dto.getCryptedPassword()).isNull();
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735486L);
  }

  @Test
  public void update_external_provider_when_reactivating_user() {
    addUser(newDisabledUser(DEFAULT_LOGIN)
      .setLocal(true)
      .setCreatedAt(PAST)
      .setUpdatedAt(PAST));
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setExternalIdentity(new ExternalIdentity("github", "john"))
      .build());
    session.commit();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getExternalIdentity()).isEqualTo("john");
    assertThat(dto.getExternalIdentityProvider()).isEqualTo("github");
    assertThat(dto.isLocal()).isFalse();
  }

  @Test
  public void fail_to_reactivate_user_if_not_disabled() {
    db.prepareDbUnit(getClass(), "fail_to_reactivate_user_if_not_disabled.xml");
    createDefaultGroup();
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("An active user with login 'marius' already exists");

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .build());
  }

  @Test
  public void associate_default_groups_when_reactivating_user() {
    db.prepareDbUnit(getClass(), "associate_default_groups_when_reactivating_user.xml");
    createDefaultGroup();

    underTest.create(NewUser.builder()
      .setLogin(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .build());
    session.commit();

    Multimap<String, String> groups = dbClient.groupMembershipDao().selectGroupsByLogins(session, asList(DEFAULT_LOGIN));
    assertThat(groups.get(DEFAULT_LOGIN).stream().anyMatch(g -> g.equals("sonar-users"))).isTrue();
  }

  @Test
  public void update_user() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    when(system2.now()).thenReturn(1418215735486L);
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma2");

    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735486L);

    List<SearchHit> indexUsers = es.getDocuments(UserIndexDefinition.INDEX, UserIndexDefinition.TYPE_USER);
    assertThat(indexUsers).hasSize(1);
    assertThat(indexUsers.get(0).getSource())
      .contains(
        entry("login", DEFAULT_LOGIN),
        entry("name", "Marius2"),
        entry("email", "marius2@mail.com"));
  }

  @Test
  public void update_user_external_identity_when_user_was_not_local() {
    addUser(UserTesting.newExternalUser(DEFAULT_LOGIN, "Marius", "marius@email.com")
      .setCreatedAt(PAST)
      .setUpdatedAt(PAST));
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@email.com")
      .setPassword(null)
      .setExternalIdentity(new ExternalIdentity("github", "john")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getExternalIdentity()).isEqualTo("john");
    assertThat(dto.getExternalIdentityProvider()).isEqualTo("github");
    assertThat(dto.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  public void update_user_external_identity_when_user_was_local() {
    addUser(UserTesting.newLocalUser(DEFAULT_LOGIN, "Marius", "marius@email.com")
      .setCreatedAt(PAST)
      .setUpdatedAt(PAST));
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@email.com")
      .setPassword(null)
      .setExternalIdentity(new ExternalIdentity("github", "john")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getExternalIdentity()).isEqualTo("john");
    assertThat(dto.getExternalIdentityProvider()).isEqualTo("github");
    // Password must be removed
    assertThat(dto.getCryptedPassword()).isNull();
    assertThat(dto.getSalt()).isNull();
    assertThat(dto.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  public void reactivate_user_on_update() {
    db.prepareDbUnit(getClass(), "reactivate_user.xml");
    when(system2.now()).thenReturn(1418215735486L);
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma2");

    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735486L);

    List<SearchHit> indexUsers = es.getDocuments(UserIndexDefinition.INDEX, UserIndexDefinition.TYPE_USER);
    assertThat(indexUsers).hasSize(1);
    assertThat(indexUsers.get(0).getSource())
      .contains(
        entry("login", DEFAULT_LOGIN),
        entry("name", "Marius2"),
        entry("email", "marius2@mail.com"));
  }

  @Test
  public void update_user_with_scm_accounts_containing_blank_entry() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("ma2", "", null)));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma2");
  }

  @Test
  public void update_only_user_name() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2"));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getName()).isEqualTo("Marius2");

    // Following fields has not changed
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma", "marius33");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_only_user_email() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setEmail("marius2@mail.com"));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma", "marius33");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_only_scm_accounts() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setScmAccounts(newArrayList("ma2")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma2");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_scm_accounts_with_same_values() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setScmAccounts(newArrayList("ma", "marius33")));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma", "marius33");
  }

  @Test
  public void remove_scm_accounts() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setScmAccounts(null));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getScmAccounts()).isNull();
  }

  @Test
  public void update_only_user_password() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setPassword("password2"));
    session.commit();
    session.clearCache();

    UserDto dto = dbClient.userDao().selectByLogin(session, DEFAULT_LOGIN);
    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getScmAccountsAsList()).containsOnly("ma", "marius33");
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
  }

  @Test
  public void fail_to_set_null_password_when_local_user() {
    addUser(UserTesting.newLocalUser(DEFAULT_LOGIN, "Marius", "marius@email.com"));
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Password can't be empty");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN).setPassword(null));
  }

  @Test
  public void fail_to_update_password_when_user_is_not_local() {
    UserDto user = newUserDto()
      .setLogin(DEFAULT_LOGIN)
      .setLocal(false);
    dbClient.userDao().insert(session, user);
    session.commit();
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Password cannot be changed when external authentication is used");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN).setPassword("password2"));
  }

  @Test
  public void not_associate_default_group_when_updating_user() {
    db.prepareDbUnit(getClass(), "associate_default_groups_when_updating_user.xml");
    createDefaultGroup();

    // Existing user, he has no group, and should not be associated to the default one
    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();

    Multimap<String, String> groups = dbClient.groupMembershipDao().selectGroupsByLogins(session, asList(DEFAULT_LOGIN));
    assertThat(groups.get(DEFAULT_LOGIN).stream().anyMatch(g -> g.equals("sonar-users"))).isFalse();
  }

  @Test
  public void not_associate_default_group_when_updating_user_if_already_existing() {
    db.prepareDbUnit(getClass(), "not_associate_default_group_when_updating_user_if_already_existing.xml");
    settings.setProperty(CORE_DEFAULT_GROUP, "sonar-users");
    session.commit();

    // User is already associate to the default group
    Multimap<String, String> groups = dbClient.groupMembershipDao().selectGroupsByLogins(session, asList(DEFAULT_LOGIN));
    assertThat(groups.get(DEFAULT_LOGIN).stream().anyMatch(g -> g.equals("sonar-users"))).isTrue();

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();

    // Nothing as changed
    groups = dbClient.groupMembershipDao().selectGroupsByLogins(session, asList(DEFAULT_LOGIN));
    assertThat(groups.get(DEFAULT_LOGIN).stream().anyMatch(g -> g.equals("sonar-users"))).isTrue();
  }

  @Test
  public void fail_to_update_user_when_scm_account_is_already_used() {
    db.prepareDbUnit(getClass(), "fail_to_update_user_when_scm_account_is_already_used.xml");
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The scm account 'jo' is already used by user(s) : 'John (john)'");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setScmAccounts(newArrayList("jo")));
  }

  @Test
  public void fail_to_update_user_when_scm_account_is_user_login() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login and email are automatically considered as SCM accounts");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN).setScmAccounts(newArrayList(DEFAULT_LOGIN)));
  }

  @Test
  public void fail_to_update_user_when_scm_account_is_existing_user_email() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login and email are automatically considered as SCM accounts");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN).setScmAccounts(newArrayList("marius@lesbronzes.fr")));
  }

  @Test
  public void fail_to_update_user_when_scm_account_is_new_user_email() {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Login and email are automatically considered as SCM accounts");

    underTest.update(UpdateUser.create(DEFAULT_LOGIN)
      .setEmail("marius@newmail.com")
      .setScmAccounts(newArrayList("marius@newmail.com")));
  }

  private void createDefaultGroup() {
    settings.setProperty(CORE_DEFAULT_GROUP, "sonar-users");
    dbClient.groupDao().insert(session, GroupTesting.newGroupDto().setName("sonar-users").setOrganizationUuid(db.getDefaultOrganization().getUuid()));
    session.commit();
  }

  private UserDto addUser(UserDto user) {
    dbClient.userDao().insert(session, user);
    session.commit();
    return user;
  }
}
