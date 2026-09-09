# statedb End User License Agreement

**Version 1.0, effective 2026-09-08**

This End User License Agreement (this "**Agreement**") is a binding legal agreement between
**STATEDB INC.**, an Ontario corporation (Ontario Corporation Number 1001702441, incorporated
under the *Business Corporations Act* (Ontario), registered office #6014, 2150 Winston Park
Drive, Unit 203, Oakville, Ontario, L6H 5V1, Canada) ("**Licensor**", "**we**", "**us**"), and
the individual or legal entity that installs, deploys, or uses the statedb software (the
"**Software**") ("**Licensee**", "**you**").

**BY ACCEPTING THIS AGREEMENT IN THE STATEDB CONSOLE, CONFIGURING A DEPLOYMENT TO ACCEPT THIS
AGREEMENT, OR INSTALLING, DEPLOYING, OR USING THE SOFTWARE, YOU AGREE TO BE BOUND BY THIS
AGREEMENT. IF YOU DO NOT AGREE, DO NOT INSTALL, DEPLOY, OR USE THE SOFTWARE.**

If you accept this Agreement on behalf of an organization, you represent that you have
authority to bind that organization, and "Licensee" means that organization.

## 1. Scope and Relationship to Other Agreements

The Software is made available under the [Elastic License 2.0 with Additional
Limitations](LICENSE) (the "**Public License**"), and may additionally be licensed under the
[Commercial License Terms](COMMERCIAL_TERMS.md) (the "**Commercial Terms**") where Licensee has
purchased a commercial subscription.

This Agreement governs Licensee's use of the Software in all cases, whether Licensee uses the
Software at no charge under the Public License or under a paid subscription. It supplements,
and does not replace, the Public License and the Commercial Terms.

Order of precedence, in the event of a conflict: (1) an executed Order Form; (2) the Commercial
Terms; (3) this Agreement; (4) the Public License. Sections 6 through 10 of this Agreement
(warranty disclaimer, no service commitment, limitation of liability, no indemnification by
Licensor, and Licensee indemnification) apply to every use of the Software and are not
displaced by the Public License.

## 2. Definitions

- "**Deployment**" has the meaning given in the Public License.
- "**Stack**" has the meaning given in the Public License.
- "**Subscription Fees**" means the recurring fees, if any, actually paid by Licensee to
  Licensor for the Software. Licensee that uses the Software at no charge under the Public
  License pays no Subscription Fees.
- "**Monthly Subscription Fee**" means the Subscription Fees attributable to a single calendar
  month of the subscription in effect immediately before the first event giving rise to a claim.
  Where fees are billed other than monthly, the Monthly Subscription Fee is the total fees for
  the billing period divided by the number of months in that period.
- "**Site Administrator**" means a user of a Deployment holding the statedb site-admin role.
- "**Licensee Data**" means any infrastructure state, configuration, secrets, credentials,
  telemetry, or other data that Licensee stores in, transmits through, or processes with the
  Software.

## 3. Acceptance and Records

Before a Deployment will create or import Stacks, this Agreement must be accepted either by a
Site Administrator in the statedb console or by the operator setting the Deployment's configured
accepted version to the exact version of this Agreement shipped with the Software. Licensor records
the accepted version, document digest, acceptance channel, acceptance timestamp, and, for console
acceptance, the accepting user in the Deployment's own database. That record is Licensee's evidence
of acceptance and Licensor's evidence of the terms in force.

Licensor may publish a new version of this Agreement with a new version identifier. A new
version applies to a Deployment when a Site Administrator accepts it in the console or when an
operator configures the Deployment to accept that exact version. Continued use of a Deployment
after the new version is accepted constitutes acceptance of that version by Licensee. The
Software may continue to permit operations other than Stack creation or import before the new
version is accepted, but that access does not waive this Agreement or create a right to use the
Software without accepting the current Agreement. Licensee's remedy if it does not agree to a new
version is to stop using the Software; no refund of prepaid fees is owed for a version Licensee
declines, except as required by applicable law.

## 4. License Grant and Restrictions

Licensee's rights to use the Software are exactly those granted by the Public License and, where
applicable, the Commercial Terms. This Agreement grants no additional license rights.

Without limiting the Public License, Licensee shall not: (a) provide the Software to third
parties as a hosted or managed service; (b) circumvent, disable, or tamper with any license key
check, Stack count limit, entitlement check, or the acceptance gate described in Section 3;
(c) remove or obscure any proprietary notice; or (d) use the Software in violation of applicable
law, including export control and sanctions law.

## 5. Licensee Responsibilities and Assumption of Risk

**The Software manages infrastructure state. Corruption, loss, or unauthorized disclosure of
that state can result in the loss or destruction of Licensee's production infrastructure.
Licensee acknowledges and accepts this risk.**

Licensee is solely responsible for: (a) deploying, configuring, securing, monitoring, patching,
and operating the Software and the database and infrastructure it runs on; (b) taking, testing,
and retaining backups of Licensee Data sufficient to recover from any failure, including
complete loss of a Deployment; (c) evaluating the Software's fitness for Licensee's purposes
before relying on it, including testing in a non-production environment; (d) managing access,
credentials, encryption keys, and secrets; (e) the acts and omissions of Licensee's users; and
(f) compliance with all laws applicable to Licensee Data.

Licensor does not host, access, monitor, or hold a copy of Licensee's Deployment or Licensee
Data. Licensor cannot recover Licensee Data and has no obligation to do so.

## 6. Warranty Disclaimer

THE SOFTWARE IS PROVIDED "**AS IS**" AND "**AS AVAILABLE**", WITH ALL FAULTS AND WITHOUT
WARRANTY OF ANY KIND. TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, LICENSOR DISCLAIMS ALL
WARRANTIES, REPRESENTATIONS, CONDITIONS, AND GUARANTEES, WHETHER EXPRESS, IMPLIED, STATUTORY,
COLLATERAL, OR OTHERWISE, INCLUDING WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF
MERCHANTABILITY, MERCHANTABLE QUALITY, FITNESS FOR A PARTICULAR PURPOSE, DURABILITY, TITLE,
NON-INFRINGEMENT, AND ANY WARRANTIES ARISING FROM A COURSE OF DEALING, USAGE, OR TRADE PRACTICE.

LICENSOR DOES NOT WARRANT THAT THE SOFTWARE WILL MEET LICENSEE'S REQUIREMENTS, THAT IT WILL BE
UNINTERRUPTED, TIMELY, SECURE, ACCURATE, OR ERROR-FREE, THAT DEFECTS WILL BE CORRECTED, THAT IT
WILL PRESERVE OR CORRECTLY REPRODUCE ANY INFRASTRUCTURE STATE, OR THAT IT IS FREE OF HARMFUL
COMPONENTS.

**THE SOFTWARE IS NOT A BACKUP SERVICE, A DISASTER-RECOVERY SERVICE, OR A SYSTEM OF RECORD.
LICENSEE MUST NOT RELY ON IT AS THE SOLE COPY OF ANY DATA IT CANNOT AFFORD TO LOSE.**

Some jurisdictions do not allow the exclusion of certain warranties or conditions. In those
jurisdictions, the exclusions above apply to the maximum extent permitted, and any warranty that
cannot be excluded is limited in duration to thirty (30) days from first installation.

## 7. No Service Level, Support, or Maintenance Commitment

Licensor offers no service level agreement, uptime commitment, response-time commitment, support
obligation, maintenance obligation, or commitment to release any future version, feature, or
fix, unless expressly stated in a separate written agreement signed by Licensor.

Support that Licensor provides informally or on a best-efforts basis is a courtesy and creates
no obligation, no warranty, and no liability. Licensor may modify, deprecate, or discontinue any
part of the Software at any time.

Licensee is solely responsible for the availability, performance, security, and operational
continuity of its Deployments and of every system that depends on them.

## 8. Limitation of Liability

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW:

**(a) Aggregate cap for paid use: one month of fees.** LICENSOR'S TOTAL AGGREGATE LIABILITY ARISING OUT OF
OR RELATED TO THIS AGREEMENT, THE PUBLIC LICENSE, THE COMMERCIAL TERMS, THE SOFTWARE, OR ITS USE
OR INABILITY TO BE USED, UNDER ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, TORT (INCLUDING
NEGLIGENCE), STRICT LIABILITY, STATUTE, OR OTHERWISE, AND FOR ALL CLAIMS IN THE AGGREGATE, SHALL
NOT EXCEED THE MONTHLY SUBSCRIPTION FEE.

This cap is aggregate across all claims and all causes of action. Multiple claims do not
increase it, and it is not reset or multiplied by the number of incidents, Deployments, Stacks,
users, or months in which incidents occur.

**(b) Exclusion of indirect and specific damages.** IN NO EVENT SHALL LICENSOR BE LIABLE FOR ANY
INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, EXEMPLARY, AGGRAVATED, OR PUNITIVE DAMAGES, OR FOR
ANY LOST PROFITS, LOST REVENUE, LOST SAVINGS, LOST BUSINESS OPPORTUNITY, LOST OR CORRUPTED DATA
OR INFRASTRUCTURE STATE, LOSS OR DESTRUCTION OF INFRASTRUCTURE, FAILED OR ERRONEOUS DEPLOYMENTS,
LOSS OF GOODWILL OR REPUTATION, BUSINESS INTERRUPTION, DOWNTIME, COST OF SUBSTITUTE GOODS OR
SERVICES, COST OF RECOVERY OR RECONSTRUCTION OF DATA OR INFRASTRUCTURE, OR THIRD-PARTY CLAIMS,
REGARDLESS OF THE THEORY OF LIABILITY AND EVEN IF LICENSOR HAS BEEN ADVISED OF THE POSSIBILITY
OF SUCH DAMAGES. THIS EXCLUSION APPLIES INDEPENDENTLY OF, AND IN ADDITION TO, THE CAP IN
SECTION 8(a).

**(c) Free use.** Where Licensee uses the Software at no charge, the Monthly Subscription Fee is
zero and Licensor's aggregate liability is CAD $0.

**(d) Failure of essential purpose.** The limitations in this Section 8 apply even if a limited
remedy fails of its essential purpose.

**(e) Basis of the bargain.** Licensee acknowledges that the Software is licensed at a price that
reflects the allocation of risk in this Agreement, that Licensor would not license the Software
on these commercial terms without these limitations, and that the limitations in Sections 6, 7,
and 8 are an essential basis of the bargain between the parties.

**(f) Carve-outs.** Nothing in this Agreement limits or excludes liability that cannot lawfully
be limited or excluded, including liability for: (i) fraud or fraudulent misrepresentation;
(ii) death or personal injury caused by negligence; or (iii) any other liability that applicable
law does not permit to be limited or excluded. Where applicable law does not permit the
exclusion or limitation of liability for gross negligence or willful misconduct, that liability
is excluded from the cap in Section 8(a) only to the minimum extent that law requires.

**(g) Time limit.** To the extent permitted by applicable law, Licensee must bring any claim
arising out of or related to this Agreement or the Software within one (1) year after the claim
arises, or the claim is permanently barred.

## 9. No Indemnification by Licensor

Licensor provides no indemnification, defense, or hold-harmless commitment of any kind,
including without limitation for any claim that the Software infringes or misappropriates a
third party's intellectual property rights. Licensee assumes all risk in this regard and is
responsible for evaluating the Software's suitability and legal fitness for its purposes.

## 10. Indemnification by Licensee

Licensee shall defend, indemnify, and hold harmless Licensor and its directors, officers,
employees, and agents from and against any third-party claim, demand, proceeding, loss, damage,
liability, and cost (including reasonable legal fees) arising out of or related to: (a)
Licensee's use of the Software in breach of this Agreement, the Public License, or the
Commercial Terms; (b) Licensee's combination of the Software with any product, service, or data
not supplied by Licensor; (c) Licensee Data; (d) Licensee's infrastructure, deployments, or
operations; or (e) Licensee's violation of applicable law or of a third party's rights.

## 11. Data and Privacy

The Software is self-hosted. Licensor does not collect, receive, or process Licensee Data in the
ordinary operation of the Software. Where Licensee voluntarily sends Licensor diagnostic
information, logs, or support material, Licensee is responsible for removing secrets, personal
information, and other sensitive content before doing so.

## 12. Term and Termination

This Agreement takes effect on the earlier of a Site Administrator's acceptance and Licensee's
first installation, deployment, or use of the Software, and continues until terminated.

Licensor may terminate this Agreement if Licensee breaches it and does not cure the breach within
thirty (30) days after written notice. Licensee may terminate at any time by ceasing all use of the
Software and deleting all copies.

On termination, Licensee's rights under this Agreement and the Commercial Terms end, and Licensee
shall cease using and delete all copies of the Software. Termination of Licensee's rights under the
Public License is governed by the Public License. Sections 2, 5, 6, 7, 8, 9, 10, 11, 12 (this
sentence), 13, and 14 survive termination.

## 13. Governing Law and Disputes

This Agreement is governed by the laws of the Province of Ontario and the federal laws of Canada
applicable in Ontario, without regard to conflict-of-laws principles. The parties attorn to the
exclusive jurisdiction of the courts of the Province of Ontario sitting in Toronto for any
dispute arising out of or related to this Agreement, except that Licensor may seek injunctive
relief in any court of competent jurisdiction.

The *United Nations Convention on Contracts for the International Sale of Goods* does not apply.
The *International Sale of Goods Act* (Ontario) does not apply.

To the extent permitted by applicable law, each party waives any right to participate in a class,
collective, or representative proceeding against the other.

## 14. General

**Entire agreement.** This Agreement, together with the Public License, the Commercial Terms, and
any Order Form, is the entire agreement between the parties regarding the Software and supersedes
all prior or contemporaneous communications, whether written or oral.

**Amendment.** Licensor may issue a new version of this Agreement as described in Section 3. No
other amendment is effective unless in writing and signed by both parties.

**Assignment.** Licensee may not assign this Agreement without Licensor's prior written consent,
except in connection with a merger, acquisition, or sale of substantially all of its assets.
Licensor may assign this Agreement freely, including to an affiliate or successor.

**Severability.** If any provision is held unenforceable, it shall be modified to the minimum
extent necessary to make it enforceable while preserving the parties' intent, and the remaining
provisions remain in full force.

**Waiver.** A party's failure to enforce a provision is not a waiver of its right to do so later.

**Force majeure.** Neither party is liable for any failure or delay in performance (other than a
payment obligation) caused by an event beyond its reasonable control.

**Relationship.** The parties are independent contractors. This Agreement creates no partnership,
joint venture, agency, or employment relationship.

**Notices.** Notices to Licensor must be in writing and sent to STATEDB INC. at its registered
office set out above. Notices to Licensee may be sent to the contact on file or displayed in the
Deployment console. Email is sufficient where the parties have exchanged addresses.

**Language.** The parties have requested that this Agreement and all related documents be drawn
up in English. *Les parties ont demandé que cette convention ainsi que tous les documents qui s'y
rattachent soient rédigés en anglais.*

---

*Version 1.0, effective 2026-09-08. The version identifier recorded on acceptance is `1.0`.*
