// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.collect.Sets.union;
import static google.registry.model.EppResourceUtils.projectResourceOntoBuilderAtTime;
import static google.registry.model.EppResourceUtils.setAutomaticTransferSuccessProperties;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.CollectionUtils;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * <p>For historical reasons, the name of this entity is "DomainBase". Ideally it would be
 * "DomainResource" for linguistic parallelism with the other {@link EppResource} entity classes,
 * but that would necessitate a complex data migration which isn't worth it.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5731">RFC 5731</a>
 */
@ReportedOn
@Entity
@ExternalMessagingName("domain")
public class DomainBase extends EppResource
    implements ForeignKeyedEppResource, ResourceWithTransferData {

  /** The max number of years that a domain can be registered for, as set by ICANN policy. */
  public static final int MAX_REGISTRATION_YEARS = 10;

  /** Status values which prohibit DNS information from being published. */
  private static final ImmutableSet<StatusValue> DNS_PUBLISHING_PROHIBITED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_HOLD,
          StatusValue.INACTIVE,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_HOLD);
  
  /**
   * Fully qualified domain name (puny-coded), which serves as the foreign key for this domain.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one domain in Datastore with this name.
   * However, there can be many domains with the same name and non-overlapping lifetimes.
   *
   * @invariant fullyQualifiedDomainName == fullyQualifiedDomainName.toLowerCase(Locale.ENGLISH)
   */
  @Index
  String fullyQualifiedDomainName;

  /** The top level domain this is under, dernormalized from {@link #fullyQualifiedDomainName}. */
  @Index
  String tld;

  /** References to hosts that are the nameservers for the domain. */
  @Index
  Set<Key<HostResource>> nsHosts;

  /**
   * The union of the contacts visible via {@link #getContacts} and {@link #getRegistrant}.
   *
   * <p>These are stored in one field so that we can query across all contacts at once.
   */
  Set<DesignatedContact> allContacts;

  /** Authorization info (aka transfer secret) of the domain. */
  DomainAuthInfo authInfo;

  /**
   * Data used to construct DS records for this domain.
   *
   * <p>This is {@literal @}XmlTransient because it needs to be returned under the "extension" tag
   * of an info response rather than inside the "infData" tag.
   */
  Set<DelegationSignerData> dsData;

  /**
   * The claims notice supplied when this application or domain was created, if there was one. It's
   * {@literal @}XmlTransient because it's not returned in an info response.
   */
  @IgnoreSave(IfNull.class)
  LaunchNotice launchNotice;

  /**
   * Name of first IDN table associated with TLD that matched the characters in this domain label.
   *
   * @see google.registry.tldconfig.idn.IdnLabelValidator#findValidIdnTableForTld
   */
  @IgnoreSave(IfNull.class)
  String idnTableName;

  /** Fully qualified host names of this domain's active subordinate hosts. */
  Set<String> subordinateHosts;

  /** When this domain's registration will expire. */
  DateTime registrationExpirationTime;

  /**
   * The poll message associated with this domain being deleted.
   *
   * <p>This field should be null if the domain is not in pending delete. If it is, the field should
   * refer to a {@link PollMessage} timed to when the domain is fully deleted. If the domain is
   * restored, the message should be deleted.
   */
  Key<PollMessage.OneTime> deletePollMessage;

  /**
   * The recurring billing event associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  Key<BillingEvent.Recurring> autorenewBillingEvent;

  /**
   * The recurring poll message associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  Key<PollMessage.Autorenew> autorenewPollMessage;

  /** The unexpired grace periods for this domain (some of which may not be active yet). */
  Set<GracePeriod> gracePeriods;

  /**
   * The id of the signed mark that was used to create this domain in sunrise.
   *
   * <p>Will only be populated for domains created in sunrise.
   */
  @IgnoreSave(IfNull.class)
  String smdId;

  /** Data about any pending or past transfers on this domain. */
  TransferData transferData;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  DateTime lastTransferTime;

  public ImmutableSet<String> getSubordinateHosts() {
    return nullToEmptyImmutableCopy(subordinateHosts);
  }

  public DateTime getRegistrationExpirationTime() {
    return registrationExpirationTime;
  }

  public Key<PollMessage.OneTime> getDeletePollMessage() {
    return deletePollMessage;
  }

  public Key<BillingEvent.Recurring> getAutorenewBillingEvent() {
    return autorenewBillingEvent;
  }

  public Key<PollMessage.Autorenew> getAutorenewPollMessage() {
    return autorenewPollMessage;
  }

  public ImmutableSet<GracePeriod> getGracePeriods() {
    return nullToEmptyImmutableCopy(gracePeriods);
  }

  public String getSmdId() {
    return smdId;
  }

  @Override
  public final TransferData getTransferData() {
    return Optional.ofNullable(transferData).orElse(TransferData.EMPTY);
  }

  @Override
  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  @Override
  public String getForeignKey() {
    return fullyQualifiedDomainName;
  }

  public String getFullyQualifiedDomainName() {
    return fullyQualifiedDomainName;
  }

  public ImmutableSet<DelegationSignerData> getDsData() {
    return nullToEmptyImmutableCopy(dsData);
  }

  public LaunchNotice getLaunchNotice() {
    return launchNotice;
  }

  public String getIdnTableName() {
    return idnTableName;
  }

  public ImmutableSet<Key<HostResource>> getNameservers() {
    return nullToEmptyImmutableCopy(nsHosts);
  }

  public final String getCurrentSponsorClientId() {
    return getPersistedCurrentSponsorClientId();
  }

  /** Returns true if DNS information should be published for the given domain. */
  public boolean shouldPublishToDns() {
    return intersection(getStatusValues(), DNS_PUBLISHING_PROHIBITED_STATUSES).isEmpty();
  }

  /**
   * Returns the Registry Grace Period Statuses for this domain.
   *
   * <p>This collects all statuses from the domain's {@link GracePeriod} entries and also adds the
   * PENDING_DELETE status if needed.
   */
  public ImmutableSet<GracePeriodStatus> getGracePeriodStatuses() {
    Set<GracePeriodStatus> gracePeriodStatuses = new HashSet<>();
    for (GracePeriod gracePeriod : getGracePeriods()) {
      gracePeriodStatuses.add(gracePeriod.getType());
    }
    if (getStatusValues().contains(StatusValue.PENDING_DELETE)
        && !gracePeriodStatuses.contains(GracePeriodStatus.REDEMPTION)) {
      gracePeriodStatuses.add(GracePeriodStatus.PENDING_DELETE);
    }
    return ImmutableSet.copyOf(gracePeriodStatuses);
  }

  /** Returns the subset of grace periods having the specified type. */
  public ImmutableSet<GracePeriod> getGracePeriodsOfType(GracePeriodStatus gracePeriodType) {
    ImmutableSet.Builder<GracePeriod> builder = new ImmutableSet.Builder<>();
    for (GracePeriod gracePeriod : getGracePeriods()) {
      if (gracePeriod.getType() == gracePeriodType) {
        builder.add(gracePeriod);
      }
    }
    return builder.build();
  }

  /**
   * The logic in this method, which handles implicit server approval of transfers, very closely
   * parallels the logic in {@code DomainTransferApproveFlow} which handles explicit client
   * approvals.
   */
  @Override
  public DomainBase cloneProjectedAtTime(final DateTime now) {

    TransferData transferData = getTransferData();
    DateTime transferExpirationTime = transferData.getPendingTransferExpirationTime();

    // If there's a pending transfer that has expired, handle it.
    if (TransferStatus.PENDING.equals(transferData.getTransferStatus())
        && isBeforeOrAt(transferExpirationTime, now)) {
      // Project until just before the transfer time. This will handle the case of an autorenew
      // before the transfer was even requested or during the request period.
      // If the transfer time is precisely the moment that the domain expires, there will not be an
      // autorenew billing event (since we end the recurrence at transfer time and recurrences are
      // exclusive of their ending), and we can just proceed with the transfer.
      DomainBase domainAtTransferTime =
          cloneProjectedAtTime(transferExpirationTime.minusMillis(1));
      // If we are within an autorenew grace period, the transfer will subsume the autorenew. There
      // will already be a cancellation written in advance by the transfer request flow, so we don't
      // need to worry about billing, but we do need to cancel out the expiration time increase.
      // The transfer period saved in the transfer data will be one year, unless the superuser
      // extension set the transfer period to zero.
      int extraYears = transferData.getTransferPeriod().getValue();
      if (domainAtTransferTime.getGracePeriodStatuses().contains(GracePeriodStatus.AUTO_RENEW)) {
        extraYears = 0;
      }
      // Set the expiration, autorenew events, and grace period for the transfer. (Transfer ends
      // all other graces).
      Builder builder = domainAtTransferTime.asBuilder()
          // Extend the registration by the correct number of years from the expiration time that
          // was current on the domain right before the transfer, capped at 10 years from the
          // moment of the transfer.
          .setRegistrationExpirationTime(extendRegistrationWithCap(
              transferExpirationTime,
              domainAtTransferTime.getRegistrationExpirationTime(),
              extraYears))
          // Set the speculatively-written new autorenew events as the domain's autorenew events.
          .setAutorenewBillingEvent(transferData.getServerApproveAutorenewEvent())
          .setAutorenewPollMessage(transferData.getServerApproveAutorenewPollMessage());
      if (transferData.getTransferPeriod().getValue() == 1) {
        // Set the grace period using a key to the prescheduled transfer billing event.  Not using
        // GracePeriod.forBillingEvent() here in order to avoid the actual Datastore fetch.
        builder.setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    transferExpirationTime.plus(
                        Registry.get(getTld()).getTransferGracePeriodLength()),
                    transferData.getGainingClientId(),
                    transferData.getServerApproveBillingEvent())));
      } else {
        // There won't be a billing event, so we don't need a grace period
        builder.setGracePeriods(ImmutableSet.of());
      }
      // Set all remaining transfer properties.
      setAutomaticTransferSuccessProperties((@org.checkerframework.checker.objectconstruction.qual.CalledMethodsTop Builder) builder, transferData);
      builder
          .setLastEppUpdateTime(transferExpirationTime)
          .setLastEppUpdateClientId(transferData.getGainingClientId());
      // Finish projecting to now.
      return builder.build().cloneProjectedAtTime(now);
    }

    Optional<DateTime> newLastEppUpdateTime = Optional.empty();

    // There is no transfer. Do any necessary autorenews for active domains.

    Builder builder = asBuilder();
    if (isBeforeOrAt(registrationExpirationTime, now) && END_OF_TIME.equals(getDeletionTime())) {
      // Autorenew by the number of years between the old expiration time and now.
      DateTime lastAutorenewTime = leapSafeAddYears(
          registrationExpirationTime,
          new Interval(registrationExpirationTime, now).toPeriod().getYears());
      DateTime newExpirationTime  = lastAutorenewTime.plusYears(1);
      builder
          .setRegistrationExpirationTime(newExpirationTime)
          .addGracePeriod(
              GracePeriod.createForRecurring(
                  GracePeriodStatus.AUTO_RENEW,
                  lastAutorenewTime.plus(Registry.get(getTld()).getAutoRenewGracePeriodLength()),
                  getCurrentSponsorClientId(),
                  autorenewBillingEvent));
      newLastEppUpdateTime = Optional.of(lastAutorenewTime);
    }

    // Remove any grace periods that have expired.
    DomainBase almostBuilt = builder.build();
    builder = almostBuilt.asBuilder();
    for (GracePeriod gracePeriod : almostBuilt.getGracePeriods()) {
      if (isBeforeOrAt(gracePeriod.getExpirationTime(), now)) {
        builder.removeGracePeriod(gracePeriod);
        if (!newLastEppUpdateTime.isPresent()
            || isBeforeOrAt(newLastEppUpdateTime.get(), gracePeriod.getExpirationTime())) {
          newLastEppUpdateTime = Optional.of(gracePeriod.getExpirationTime());
        }
      }
    }

    // It is possible that the lastEppUpdateClientId is different from current sponsor client
    // id, so we have to do the comparison instead of having one variable just storing the most
    // recent time.
    if (newLastEppUpdateTime.isPresent()) {
      if (getLastEppUpdateTime() == null
          || newLastEppUpdateTime.get().isAfter(getLastEppUpdateTime())) {
        builder
            .setLastEppUpdateTime(newLastEppUpdateTime.get())
            .setLastEppUpdateClientId(getCurrentSponsorClientId());
      }
    }

    // Handle common properties like setting or unsetting linked status. This also handles the
    // general case of pending transfers for other resource types, but since we've always handled
    // a pending transfer by this point that's a no-op for domains.
    projectResourceOntoBuilderAtTime(almostBuilt, builder, now);
    return builder.build();
  }

  /** Return what the expiration time would be if the given number of years were added to it. */
  public static DateTime extendRegistrationWithCap(
      DateTime now,
      DateTime currentExpirationTime,
      @Nullable Integer extendedRegistrationYears) {
    // We must cap registration at the max years (aka 10), even if that truncates the last year.
    return earliestOf(
        leapSafeAddYears(
            currentExpirationTime,
            Optional.ofNullable(extendedRegistrationYears).orElse(0)),
        leapSafeAddYears(now, MAX_REGISTRATION_YEARS));
  }

  /** Loads and returns the fully qualified host names of all linked nameservers. */
  public ImmutableSortedSet<String> loadNameserverFullyQualifiedHostNames() {
    return ofy()
        .load()
        .keys(getNameservers())
        .values()
        .stream()
        .map(HostResource::getFullyQualifiedHostName)
        .collect(toImmutableSortedSet(Ordering.natural()));
  }

  /** A key to the registrant who registered this domain. */
  public Key<ContactResource> getRegistrant() {
    return nullToEmpty(allContacts)
        .stream()
        .filter(IS_REGISTRANT)
        .findFirst()
        .get()
        .getContactKey();
  }

  /** Associated contacts for the domain (other than registrant). */
  public ImmutableSet<DesignatedContact> getContacts() {
    return nullToEmpty(allContacts)
        .stream()
        .filter(IS_REGISTRANT.negate())
        .collect(toImmutableSet());
  }

  public DomainAuthInfo getAuthInfo() {
    return authInfo;
  }

  /** Returns all referenced contacts from this domain or application. */
  public ImmutableSet<Key<ContactResource>> getReferencedContacts() {
    return nullToEmptyImmutableCopy(allContacts)
        .stream()
        .map(DesignatedContact::getContactKey)
        .filter(Objects::nonNull)
        .collect(toImmutableSet());
  }

  public String getTld() {
    return tld;
  }

  /** Predicate to determine if a given {@link DesignatedContact} is the registrant. */
  private static final Predicate<DesignatedContact> IS_REGISTRANT =
      (DesignatedContact contact) -> DesignatedContact.Type.REGISTRANT.equals(contact.type);

  /** An override of {@link EppResource#asBuilder} with tighter typing. */
  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link DomainBase}, since it is immutable. */
  public static class Builder extends EppResource.Builder<DomainBase, Builder>
      implements BuilderWithTransferData<Builder> {

    public Builder() {}

    Builder(DomainBase instance) {
      super(instance);
    }

    @Override
    public DomainBase build() {
      DomainBase instance = getInstance();
      // If TransferData is totally empty, set it to null.
      if (TransferData.EMPTY.equals(getInstance().transferData)) {
        setTransferData(null);
      }
      // A DomainBase has status INACTIVE if there are no nameservers.
      if (getInstance().getNameservers().isEmpty()) {
        addStatusValue(StatusValue.INACTIVE);
      } else {  // There are nameservers, so make sure INACTIVE isn't there.
        removeStatusValue(StatusValue.INACTIVE);
      }
      
      checkArgumentNotNull(
          emptyToNull(instance.fullyQualifiedDomainName), "Missing fullyQualifiedDomainName");
      checkArgument(instance.allContacts.stream().anyMatch(IS_REGISTRANT), "Missing registrant");
      instance.tld = getTldFromDomainName(instance.fullyQualifiedDomainName);
      return super.build();
    }

    public Builder setFullyQualifiedDomainName(String fullyQualifiedDomainName) {
      checkArgument(
          fullyQualifiedDomainName.equals(canonicalizeDomainName(fullyQualifiedDomainName)),
          "Domain name must be in puny-coded, lower-case form");
      getInstance().fullyQualifiedDomainName = fullyQualifiedDomainName;
      return thisCastToDerived();
    }

    public Builder setDsData(ImmutableSet<DelegationSignerData> dsData) {
      getInstance().dsData = dsData;
      return thisCastToDerived();
    }

    public Builder setRegistrant(Key<ContactResource> registrant) {
      // Replace the registrant contact inside allContacts.
      getInstance().allContacts = union(
          getInstance().getContacts(),
          DesignatedContact.create(Type.REGISTRANT, checkArgumentNotNull(registrant)));
      return thisCastToDerived();
    }

    public Builder setAuthInfo(DomainAuthInfo authInfo) {
      getInstance().authInfo = authInfo;
      return thisCastToDerived();
    }

    public Builder setNameservers(Key<HostResource> nameserver) {
      getInstance().nsHosts = ImmutableSet.of(nameserver);
      return thisCastToDerived();
    }

    public Builder setNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      getInstance().nsHosts = forceEmptyToNull(nameservers);
      return thisCastToDerived();
    }

    public Builder addNameserver(Key<HostResource> nameserver) {
      return addNameservers(ImmutableSet.of(nameserver));
    }

    public Builder addNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(union(getInstance().getNameservers(), nameservers)));
    }

    public Builder removeNameserver(Key<HostResource> nameserver) {
      return removeNameservers(ImmutableSet.of(nameserver));
    }

    public Builder removeNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(difference(getInstance().getNameservers(), nameservers)));
    }

    public Builder setContacts(DesignatedContact contact) {
      return setContacts(ImmutableSet.of(contact));
    }

    public Builder setContacts(ImmutableSet<DesignatedContact> contacts) {
      checkArgument(contacts.stream().noneMatch(IS_REGISTRANT), "Registrant cannot be a contact");
      // Replace the non-registrant contacts inside allContacts.
      getInstance().allContacts =
          Streams.concat(
                  nullToEmpty(getInstance().allContacts).stream().filter(IS_REGISTRANT),
                  contacts.stream())
              .collect(toImmutableSet());
      return thisCastToDerived();
    }

    public Builder addContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(union(getInstance().getContacts(), contacts)));
    }

    public Builder removeContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(difference(getInstance().getContacts(), contacts)));
    }

    public Builder setLaunchNotice(LaunchNotice launchNotice) {
      getInstance().launchNotice = launchNotice;
      return thisCastToDerived();
    }

    public Builder setIdnTableName(String idnTableName) {
      getInstance().idnTableName = idnTableName;
      return thisCastToDerived();
    }

    public Builder setSubordinateHosts(ImmutableSet<String> subordinateHosts) {
      getInstance().subordinateHosts = subordinateHosts;
      return thisCastToDerived();
    }

    public Builder addSubordinateHost(String hostToAdd) {
      return setSubordinateHosts(ImmutableSet.copyOf(
          union(getInstance().getSubordinateHosts(), hostToAdd)));
    }

    public Builder removeSubordinateHost(String hostToRemove) {
      return setSubordinateHosts(ImmutableSet.copyOf(
          CollectionUtils.difference(getInstance().getSubordinateHosts(), hostToRemove)));
    }

    public Builder setRegistrationExpirationTime(DateTime registrationExpirationTime) {
      getInstance().registrationExpirationTime = registrationExpirationTime;
      return this;
    }

    public Builder setDeletePollMessage(Key<PollMessage.OneTime> deletePollMessage) {
      getInstance().deletePollMessage = deletePollMessage;
      return this;
    }

    public Builder setAutorenewBillingEvent(
        Key<BillingEvent.Recurring> autorenewBillingEvent) {
      getInstance().autorenewBillingEvent = autorenewBillingEvent;
      return this;
    }

    public Builder setAutorenewPollMessage(
        Key<PollMessage.Autorenew> autorenewPollMessage) {
      getInstance().autorenewPollMessage = autorenewPollMessage;
      return this;
    }

    public Builder setSmdId(String smdId) {
      getInstance().smdId = smdId;
      return this;
    }

    public Builder setGracePeriods(ImmutableSet<GracePeriod> gracePeriods) {
      getInstance().gracePeriods = gracePeriods;
      return this;
    }

    public Builder addGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods = union(getInstance().getGracePeriods(), gracePeriod);
      return this;
    }

    public Builder removeGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods = CollectionUtils
          .difference(getInstance().getGracePeriods(), gracePeriod);
      return this;
    }

    @Override
    public Builder setTransferData(TransferData transferData) {
      getInstance().transferData = transferData;
      return thisCastToDerived();
    }

    @Override
    public Builder setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return thisCastToDerived();
    }
  }
}
