[Music]
this is the first cell of a
multicellular organism it contains all
the information needed to build this
organism but if the cells simply divide
to create copies of themselves we will
end up with just a clump of identical
cells however we want these cells to
form different tissues and organs in the
body more than that we want it to be not
just a lump of different tissues but
also have the right shape
to understand how this happens I began a
new project simulating how the cells of
a multicellular organism interact with
each
other but first a bit of theoretical
background it's a DNA
molecule sometimes a gene is found on it
a gene contains instructions for
producing a protein or functional RNA
depending on which set of genes are
active in the cell we get different
types of
cells in addition to genes there are
also various regulatory sequences in DNA
that regulate the work of
genes these regulatory sequences
themselves are controlled by the active
molecules that are present in the cell
in this project I'll refer to them as
morphogens usually they are proteins or
rnas they can either be produced within
the cell itself or enter from outside as
a result we can get complex Gene
regulatory Network where different genes
influence each
other by using positive and negative
feedback and time delays it's possible
to create triggers filters oscillators
timers and
others several morphogens may be
required to regulate a single Gene the
same morphen can act both as an
activator and a repressor depending on
the conditions a gene can not only be
turned on or off but also have its
activity regulated at the molecular
level regulation looks quite intricate
the DNA molecule can bend so regulatory
sequences can be located far away from
the gene they control this makes it
difficult to study
them moreover regulation can occur not
only at the stage of reading information
from DNA but also at later stages there
are several ways for cell to communicate
with each other today we'll use the
option where a cell releases active
molecules into the intercellular
environment creating a concentration
gradient around itself in future videos
we'll use the first option as well the
range isn't very large in my project
I've limited it to nine cells
neighboring cells can react not only to
the presence of a morphogen but also to
its
concentration the exceedingly complex
Gene circuit that specifies a portion of
the developing sea urchin embryo each
colored small box represents a different
Gene those in yellow code for
transcription regulators and those in
green and blue code for proteins that
give cells of the mesoderm and endoderm
respectively their specialized
characteristics in the video for the
sake of importance I'm using images from
this
book in my project everything has been
greatly simplified and reduced to a
simple scheme additionally my cells have
a fixed size and position on a hexagonal
grid so cell division can only happen
along the edge of the organism that
means my simulation is quite distant
from what actually occurs in real
organisms my main focus was on the
interaction between cells so sometimes I
see this as a swarm intelligence
project today let's try to create
something similar to plazo with the
ability to generate and
reproduce here is a list of the genes we
need for the project the first 14 genes
produce the morphogens necessary for
regulation they are all numbered
according to the gene number by default
they are inactive for them to work
certain morphogens must must be present
in the cell it's worth noting that
morphogens degrade over time so they
need to be constantly produced to
maintain the necessary
concentration for convenience I've
divided them into two groups the first
six genes will produce a morphen that
doesn't leave the cell and activates the
same gene let's record this in the
regulatory
sequences this creates a positive
feedback loop where the activated Gene
sustains its own work I can use these
genes as memory and I'll sometimes refer
to them as triggers the following genes
will be necessary for interaction with
other
cells each of these genes produces its
own morphogen which can penetrate into
adjacent cells when activated you need
to specify how strongly the gene
expresses meaning how far the product of
its activity will spread across
neighboring cells the maximum distance
is nine
cells the next two genes start
timers when a gene is activated you need
to specify the duration of the timer at
the end of its run the corresponding
morphogen will appear in the cell if the
cell divides the timer remains active
only in the Mother
cell all cells divide when there is such
an opportunity by keeping the next Gene
active the process of cell division can
be
blocked activating the next Gene leads
to apoptosis meaning the cell kills
itself
when activated you need to specify a
number inversely proportional to the
probability of this happening during one
step of the simulation the following two
genes trigger a Cascade of genes that
produce cellular
differentiation let's look at this in a
little more detail today we will use
three types of cells germline cells or
stem cells in reality they differ but in
this project they are the same cell and
then there are two types of somatic
cells flesh cells which perform the
primary task of providing resources for
the organism skin cells whose main
function is to protect the organism from
the environment each of these cells when
dividing produces a cell of the same
type however a stem cell can transform
into any other type of cell complex
Transformations take place unnecessary
genes are blocked through methylation
and returning to the state of a stem
cell or transitioning to another type is
usually no longer possible in reality
differentiation can occur in multiple
stages as shown in the image using blood
cells as an
example each cell type has its specific
morphogen which we'll use to identify
the cell type during
regulation activation of the last gene
causes the cell if possible to move away
from the 12
morphogen here are all the morphogens
that we will need need let's add another
morphen which indicates the number of
neighbors a cell has to describe the
regulatory sequence I'll use this method
if it's a stem cell and the sixth and
10th morphogens are present and the cell
is fully surrounded activate the gene if
the Gene needs to be activated with a
specific intensity we'll indicate that
after the condition if you need to
invert the conditions then it is written
on a pink background that is the gene
will be activated only in the absence of
the sixth
morphen now it's time to create our
first
organism if we initiate cell division
now we will get a growing mass of
identical cells it is necessary to limit
the growth somehow let the first cell
secrete a morphogen which will serve as
a growth
boundary the first naive solution let
the cell if it is a stem cell and has no
neighbors begin to produce a sixth
morphen for a range of seven cells but
as soon as the cell divides the
conditions will no longer be met and the
production of the morphen will
cease let's go the other way the absence
of neighbors will turn on the zeroth
gene which will be used as a trigger and
the cell with the active trigger will
produce the six
morphen this won't work either the
zeroth gene will be active in every cell
after division
in this project asymmetric division only
works for the timer and the timer will
only function in the parent
cell therefore we will first start the
timer and upon completion of its work
the timer will activate the zeroth
gene if the timer duration is set too
large the dividing cells will leave the
morphogen area of influence if it's too
small the first cell won't have time to
be surrounded by neighbors and will also
start
dividing we keep the timer duration
short and introduce a ban on cell
division for those that have the zeroth
gene
activated now everything is working
correctly all that's left is to add a
restriction on cell division for those
that have moved beyond the range of the
sixth
morphen and nothing starts the first
cell refuses to divide because there is
no sixth
morphen we'll have to adjust the
condition slightly for cell division to
stop not only must the sixth morphen be
ENT but the first Gene must also be
active and the first Gene will be
activated by the sixth
morphen now there's nothing stopping the
first cell from
dividing at the end of the timer the
sixth morphen will begin to be produced
which will then activate the first Gene
in all
cells upon reaching the boundary of the
morphogen the cells will stop dividing
it's time for cell
differentiation once they reach the
boundary of the sixth morphen the cells
will start turning into skin
cells will prohibit skin cells from
dividing before transforming into a skin
cell each cell will release a small
amount of the seventh morphen this will
serve as a differentiation signal for
the other
cells sometimes I change the color
scheme of the morphogens to better
visualize their
operation the seventh morphen will
trigger the transformation process into
to flesh cells for those under the
influence of the six
morphen only the central cell with the
activated zeroth Gene will remain in its
original state it will become the
germline cell for producing future
organisms will restrict the division of
Flesh cells in the absence of the sixth
morphogen the skin turned out to be
perforated currently the organism is
capable of
generation if the central cell is
removed all stem cells will turn into
skin cells due to the lack of the sixth
morphen to complete cell differentiation
will add a wave of the seventh morphen
stem cells upon receiving the seventh
morphen also release a portion of the
seventh morphen and transform into flesh
cells a wave of transformation sweeps
through the
organism here for some reason two
germline cells remain but that's
perfectly fine for us
the skin is perforated and does not heal
when damaged so we'll need to fix that
somehow let's add an additional
condition to the differentiation process
into a flesh cell the cell must be
completely surrounded by other
cells this allows the skin to form
without holes but it doesn't solve the
Regeneration problem we'll come back to
this
later now we're going to make
significant changes to the genome to
make the organism larger
the first cells that reach the boundary
of the sixth morphogen will become
germline cells to do this we'll activate
the zeroth gene in them in response B ID
a burst of the eighth
morphogen the eighth morphogen activates
the second Gene in all cells except the
germline
cells to transform into skin now
requires the activation of the second
Gene those cells that were the first to
reach the boundary of the sixth
morphogen become germline cells using
the eth morphogen they disable this
privilege in the rest of the cells by
activating their second Gene cells with
the active second Gene upon reaching the
boundary of the sixth morphogen
transform into skin
cells we've got a perfect organism the
skin protects it from external
influences flesh cells gather resources
and energy and send them along the
gradient of the morphogen to the
germline cells
flesh cells are able to regenerate but
if a germline cell is removed some
worker cells find themselves without a
purpose and don't know where to transfer
the collected resources and energy we'll
monitor the figure and get rid of such
cells now in addition to the sixth
morphen germline cells will also secrete
the 13th morphen but at a slightly lower
intensity flesh cells will stop dividing
when there is no 13th morphen and will
die in the absence of the sixth
morphen flesh cells no longer intrude
where the skin should
be if a germline cell is removed all
Associated flesh cells die the skin
still needs some work let flesh cells
that have fewer than six Neighbors start
secreting the ninth
morphogen skin cells under the influence
of the ninth morphen gain the ability to
divide
now the skin has the ability to
regenerate beautiful scars in the form
of thickened skin
remain but if the central cell is
removed a huge unnecessary growth of
skin cells
forms each flesh cell will secrete the
tenth morphogen over a distance of one
cell and a skin cell dies in the absence
of this morphen
regeneration works well after
regeneration the skin becomes smooth and
silky and after the death of the
germline cell everything is neatly
sealed
up it's time to think about
reproduction we have several germline
cells and all other somatic cells must
die we will use the 11th morphen for
this suppose one of the germline cells
has accumulated enough resources to
create a new organism its third Gene
gets activated I'll do this
manually the third Gene will launch a
wave of morphen and deactivate itself on
the right side I'll record the
regulatory sequences that deactivate the
gene to ensure the morphen wave travels
throughout the entire organism every
cell reached by the 11th morphogen will
also release
it when the organism has amassed enough
resources to create new copies of itself
I activate the third Gene in one of the
germline cells a wave of the 11th
morphogen swept through triggering
apoptosis in all somatic cells the
remaining cells have the zeroth gene
activated so they cannot divide or
produce the sixth morphen Additionally
the 11th Gene is operational which
activates itself here we've created
positive
feedback let's remove it now only
somatic cells will produce this
morphen also let the wave of the 11th
morphen deactivate all functioning
triggers in the germline
cells launch the apoptosis
wave note that the germline cell divided
without starting the timer or activating
the necessary
triggers the same thing happened with
another
cell that means the development process
of the new organism was disrupted from
the very beginning as a result we ended
up with just a bunch of dividing cells
to Kickstart the proper development of
this organism the first cell shouldn't
have any
neighbors so for now we need to
temporarily block the division of
germline
cells activation of the third Gene will
also activate the fourth Gene a cell
with the active fourth Gene will secrete
12
morphen the 11th morphen which causes
apoptosis in somatic cells will also
activate the fifth Gene
it will block cell division and the
initiation of the zeroth
timer in the end we are left with
germline cells the active fifth Gene
blocks their further actions while the
cell that initiated the replication
process releases a morphogen that sets
the direction for all cells to
disperse final touches in the germline
cells the first timer is also activated
for a long
period at the end of the timer's run it
deactivates the fourth and fifth
genes deactivation of the fifth Gene
unblocks the zeroth timer which
initiates the formation of a new
organism while the fifth Gene was active
all single cells would move away from
the 12 morphen
we've successfully created several new
organisms theoretically this is already
a solid foundation for creating a
simulation of artificial life but I have
different plans for
now all the cells in a multicellular
organism are clones of a single cell the
vast majority of them are initially
destined to die and their main task is
to provide resources for the germline
cells which will pass the genome onto
subsequent
Generations remember that all cells
contain the same genome having only the
genome it is nearly impossible to
understand what kind of organism it is
you can only find out by launching the
development of the
organism I want to draw your attention
to the fact that the interaction of
cells within a multicellular organism is
fully analogous to swarm intelligence
where multiple agents sharing the same
algorithm achieve something greater
through Collective activity than what
could be seen in the algorithm alone
in the next video let's try to create
more complex organisms that represent
not just a shapeless spot but have a
sophisticated form to do this we'll need
to give the cells new
capabilities we'll experiment with
random mutations in the regulatory
sequences and see where that leads with
the same set of genes but activating
them differently you can obtain numerous
distinct organisms this raises the
question what's most important in the
genome
be sure to like subscribe and leave a
comment a special thank you to those
supporting me on patreon bye for now
[Music]
[Music]
n
