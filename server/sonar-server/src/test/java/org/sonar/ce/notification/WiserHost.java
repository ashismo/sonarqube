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
package org.sonar.ce.notification;

import java.io.IOException;
import java.util.ArrayList;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.subethamail.wiser.Wiser;
import org.subethamail.wiser.WiserMessage;

public class WiserHost {
  private static final Logger LOG = Loggers.get(WiserHost.class);

  private static Wiser smtpServer;

  @BeforeClass
  public static void setUp() throws InterruptedException {
    smtpServer = new Wiser(0);
    smtpServer.start();
    System.out.println("SMTP Server port: " + smtpServer.getServer().getPort());

  }

  @AfterClass
  public static void tearDown() throws Exception {
    System.out.println("tearDown called");
    if (smtpServer != null) {
      smtpServer.stop();
    }
  }

  @Test
  public void name() throws InterruptedException {

    while (true) {
      Thread.sleep(1_000);
      ArrayList<WiserMessage> wiserMessages = new ArrayList<>(smtpServer.getMessages());
      smtpServer.getMessages().clear();
      wiserMessages.forEach(m -> {
        try {
          MimeMessage mimeMessage = m.getMimeMessage();
          LOG.info(String.format(
            "subject=%s%n" +
              "content=%s%n",
            mimeMessage.getSubject(),
            mimeMessage.getContent()));
        } catch (MessagingException | IOException e) {
          e.printStackTrace();
        }

      });
    }
  }

  @Test
  public void testRetrhrow() {

    try {
      throw new RuntimeException("giving it a try");
    } catch (RuntimeException e) {
      throw e;
    }
  }
}
