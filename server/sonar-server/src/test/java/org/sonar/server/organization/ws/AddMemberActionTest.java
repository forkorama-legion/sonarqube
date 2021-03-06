/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
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

package org.sonar.server.organization.ws;

import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.MapSettings;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.user.index.UserDoc;
import org.sonar.server.user.index.UserIndex;
import org.sonar.server.user.index.UserIndexDefinition;
import org.sonar.server.user.index.UserIndexer;
import org.sonar.server.user.index.UserQuery;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;

import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.core.util.Protobuf.setNullable;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER_QUALITY_GATES;
import static org.sonar.server.organization.ws.OrganizationsWsSupport.PARAM_ORGANIZATION;

public class AddMemberActionTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone().logIn().setRoot();
  @Rule
  public EsTester es = new EsTester(new UserIndexDefinition(new MapSettings()));
  private UserIndex userIndex = new UserIndex(es.client());
  @Rule
  public DbTester db = DbTester.create();
  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();


  private WsActionTester ws = new WsActionTester(new AddMemberAction(dbClient, userSession, new UserIndexer(dbClient, es.client())));

  private OrganizationDto organization;
  private UserDto user;

  @Before
  public void setUp() {
    organization = db.organizations().insert();
    user = db.users().insertUser();
  }

  @Test
  public void definition() {
    WebService.Action definition = ws.getDef();

    assertThat(definition.key()).isEqualTo("add_member");
    assertThat(definition.since()).isEqualTo("6.4");
    assertThat(definition.isPost()).isTrue();
    assertThat(definition.isInternal()).isTrue();
    assertThat(definition.params()).extracting(WebService.Param::key).containsOnly("organization", "login");

    WebService.Param organization = definition.param("organization");
    assertThat(organization.isRequired()).isTrue();

    WebService.Param login = definition.param("login");
    assertThat(login.isRequired()).isTrue();
  }

  @Test
  public void no_content_http_204_returned() {
    TestResponse result = call(organization.getKey(), user.getLogin());

    assertThat(result.getStatus()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(result.getInput()).isEmpty();
  }

  @Test
  public void add_member_in_db_and_user_index() {
    call(organization.getKey(), user.getLogin());

    assertMember(organization.getUuid(), user.getId());
    List<UserDoc> userDocs = userIndex.search(UserQuery.builder().build(), new SearchOptions()).getDocs();
    assertThat(userDocs).hasSize(1);
    assertThat(userDocs.get(0).organizationUuids()).containsOnly(organization.getUuid(), db.getDefaultOrganization().getUuid());
  }

  @Test
  public void user_can_be_member_of_two_organizations() {
    OrganizationDto anotherOrg = db.organizations().insert();

    call(organization.getKey(), user.getLogin());
    call(anotherOrg.getKey(), user.getLogin());

    assertMember(organization.getUuid(), user.getId());
    assertMember(anotherOrg.getUuid(), user.getId());
  }

  @Test
  public void add_member_as_organization_admin() {
    userSession.logIn().addPermission(ADMINISTER, organization);

    call(organization.getKey(), user.getLogin());

    assertMember(organization.getUuid(), user.getId());
  }

  @Test
  public void fail_if_login_does_not_exist() {
    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("User 'login-42' is not found");

    call(organization.getKey(), "login-42");
  }

  @Test
  public void fail_if_organization_does_not_exist() {
    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Organization 'org-42' is not found");

    call("org-42", user.getLogin());
  }

  @Test
  public void fail_if_no_login_provided() {
    expectedException.expect(IllegalArgumentException.class);

    call(organization.getKey(), null);
  }

  @Test
  public void fail_if_no_organization_provided() {
    expectedException.expect(IllegalArgumentException.class);

    call(null, user.getLogin());
  }

  @Test
  public void fail_if_user_already_added_in_organization() {
    call(organization.getKey(), user.getLogin());

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("User '" + user.getLogin() + "' is already a member of organization '" + organization.getKey() + "'");

    call(organization.getKey(), user.getLogin());
  }

  @Test
  public void fail_if_insufficient_permissions() {
    userSession.logIn().addPermission(ADMINISTER_QUALITY_GATES, organization);

    expectedException.expect(ForbiddenException.class);

    call(organization.getKey(), user.getLogin());
  }

  private TestResponse call(@Nullable String organizationKey, @Nullable String login) {
    TestRequest request = ws.newRequest();
    setNullable(organizationKey, o -> request.setParam(PARAM_ORGANIZATION, o));
    setNullable(login, l -> request.setParam("login", l));

    return request.execute();
  }

  private void assertMember(String organizationUuid, int userId) {
    assertThat(dbClient.organizationMemberDao().select(dbSession, organizationUuid, userId)).isPresent();
  }
}
