package com.aishell.host;

import com.aishell.host.HostService.HostRequest;
import com.aishell.host.HostService.HostView;
import com.aishell.host.HostService.ProfileRequest;
import com.aishell.host.HostService.ProfileView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HostController {

  private final HostService hosts;

  public HostController(HostService hosts) {
    this.hosts = hosts;
  }

  @GetMapping("/api/hosts")
  public List<HostView> list() {
    return hosts.list();
  }

  @PostMapping("/api/hosts")
  @ResponseStatus(HttpStatus.CREATED)
  public HostView create(@RequestBody(required = false) HostRequest request) {
    return hosts.createHost(request);
  }

  @PutMapping("/api/hosts/{id}")
  public HostView update(@PathVariable String id, @RequestBody(required = false) HostRequest request) {
    return hosts.updateHost(id, request);
  }

  @DeleteMapping("/api/hosts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    hosts.deleteHost(id);
  }

  @PostMapping("/api/hosts/{hostId}/profiles")
  @ResponseStatus(HttpStatus.CREATED)
  public ProfileView addProfile(
      @PathVariable String hostId, @RequestBody(required = false) ProfileRequest request) {
    return hosts.addProfile(hostId, request);
  }

  @PutMapping("/api/profiles/{id}")
  public ProfileView updateProfile(
      @PathVariable String id, @RequestBody(required = false) ProfileRequest request) {
    return hosts.updateProfile(id, request);
  }

  @DeleteMapping("/api/profiles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteProfile(@PathVariable String id) {
    hosts.deleteProfile(id);
  }
}
