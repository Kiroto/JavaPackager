package io.github.fvarrui.javapackager.model;

import java.io.Serializable;

public class NotarizationConfig implements Serializable {
    private String appleID;
    private String teamID;
    private String appSpecificPassword;

    public String getAppleID() {
        return appleID;
    }

    public void setAppleID(String appleID) {
        this.appleID = appleID;
    }

    public String getTeamID() {
        return teamID;
    }

    public void setTeamID(String teamID) {
        this.teamID = teamID;
    }

    public String getAppSpecificPassword() {
        return appSpecificPassword;
    }

    public void setAppSpecificPassword(String appSpecificPassword) {
        this.appSpecificPassword = appSpecificPassword;
    }

    public Boolean areConfigurationsValid() {
        return getAppleID() != null && getTeamID() != null && getAppSpecificPassword() != null;
    }
}
